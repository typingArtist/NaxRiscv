package naxriscv.lsu

import naxriscv.Frontend.{DECODE_COUNT, DISPATCH_COUNT, DISPATCH_MASK}
import naxriscv.{Fetch, Frontend, Global, ROB}
import naxriscv.frontend.{DispatchPlugin, FrontendPlugin}
import naxriscv.interfaces._
import naxriscv.riscv.{AtomicAlu, CSR, Rvi}
import naxriscv.utilities.{AddressToMask, DocPlugin, Plugin}
import spinal.core._
import spinal.lib._
import spinal.lib.pipeline.Connection.M2S
import spinal.lib.pipeline.{Pipeline, Stageable, StageableOffset}

import scala.collection.mutable.ArrayBuffer
import naxriscv.Global._
import naxriscv.execute.EnvCallPlugin
import naxriscv.fetch.FetchPlugin
import naxriscv.interfaces.AddressTranslationPortUsage.LOAD_STORE
import naxriscv.misc.RobPlugin
import spinal.core.fiber.Handle
import spinal.lib.fsm._

object LsuUtils{
  def sizeWidth(wordWidth : Int) = log2Up(log2Up(wordWidth/8)+1)
}

case class LsuLoadPort(lqSize : Int, wordWidth : Int, physicalRdWidth : Int, pcWidth : Int) extends Bundle {
  val robId = ROB.ID()
  val lqId = UInt(log2Up(lqSize) bits)
  val address = UInt(Global.XLEN bits)
  val size = UInt(log2Up(log2Up(wordWidth/8)+1) bits)
  val unsigned = Bool()
  val physicalRd = UInt(physicalRdWidth bits)
  val writeRd = Bool()
  val pc = UInt(pcWidth bits)
  val lr = Bool()

  val earlySample = Bool()
  val earlyPc = UInt(pcWidth bits) //One cycle early pc, to fetch prediction
}

case class LsuStorePort(sqSize : Int, wordWidth : Int, physicalRdWidth : Int) extends Bundle {
  val robId = ROB.ID()
  val sqId = UInt(log2Up(sqSize) bits)
  val address = UInt(Global.XLEN bits)
  val data = Bits(wordWidth bits)
  val size = UInt(log2Up(log2Up(wordWidth/8)+1) bits)

  //Atomic stuff
  val amo = Bool()
  val sc = Bool()
  val swap = Bool()
  val op  = Bits(3 bits)
  val physicalRd = UInt(physicalRdWidth bits)
  val writeRd = Bool()
}

case class LsuPeripheralBusParameter(addressWidth : Int,
                                     dataWidth : Int)

case class LsuPeripheralBusCmd(p : LsuPeripheralBusParameter) extends Bundle{
  val write = Bool()
  val address = UInt(p.addressWidth bits)
  val data = Bits(p.dataWidth bits)
  val mask = Bits(p.dataWidth / 8 bit)
  val size = UInt(log2Up(log2Up(p.dataWidth/8)+1) bits)
}

case class LsuPeripheralBusRsp(p : LsuPeripheralBusParameter) extends Bundle{
  val error = Bool()
  val data = Bits(p.dataWidth bits)
}

object LsuPeripheralBus{
  def apply(addressWidth : Int, dataWidth : Int) : LsuPeripheralBus = LsuPeripheralBus(LsuPeripheralBusParameter(addressWidth,dataWidth))
}

case class LsuPeripheralBus(p : LsuPeripheralBusParameter) extends Bundle with IMasterSlave {
  val cmd = Stream(LsuPeripheralBusCmd(p))
  val rsp = Flow(LsuPeripheralBusRsp(p))

  override def asMaster(): Unit = {
    master(cmd)
    slave(rsp)
  }
}


class LsuPlugin(lqSize: Int,
                sqSize : Int,
                //                storeToLoadBypass : Boolean,
                translationStorageParameter : Any,
                loadTranslationParameter : Any,
                storeTranslationParameter : Any,
                hazardPedictionEntries : Int,
                hazardPredictionTagWidth : Int,
                hitPedictionEntries : Int,
                hitPredictionCounterWidth : Int = 6,
                hitPredictionErrorPenality : Int = 20,
                loadToCacheBypass : Boolean = true,  //Reduce the load latency by one cycle. When the LoadPlugin calculate the address it directly start the query the cache
                lqToCachePipelined : Boolean = true, //Add one additional stage between LQ arbitration and the cache query
                loadFeedAt : Int = 0, //Stage at which the d$ cmd is sent
                loadCheckSqAt : Int = 1,
                loadCtrlAt : Int = 3) extends Plugin with LockedImpl with WakeRobService with WakeRegFileService with PostCommitBusy{

  val wordWidth = Global.XLEN.get
  val wordBytes = wordWidth/8
  val wordSizeWidth = LsuUtils.sizeWidth(wordWidth)
  val pageOffsetRange = 11 downto log2Up(wordBytes)
  val pageNumberRange = Global.XLEN.get-1 downto 12
  val pageOffsetWidth = pageOffsetRange.size
  val pageNumberWidth = pageNumberRange.size
  override def postCommitBusy = setup.postCommitBusy

  val peripheralBus = create late master(LsuPeripheralBus(postWidth, wordWidth))

  def postWidth = getService[AddressTranslationService].postWidth
  def virtualAddressWidth = getService[AddressTranslationService].preWidth

  case class StorePortSpec(port : Flow[LsuStorePort])
  val storePorts = ArrayBuffer[StorePortSpec]()
  def newStorePort(): Flow[LsuStorePort] = {
    val physicalRdWidth = getService[DecoderService].PHYS_RD
    storePorts.addRet(StorePortSpec(Flow(LsuStorePort(sqSize, wordWidth,  widthOf(physicalRdWidth))))).port
  }

  case class LoadPortSpec(port : Flow[LsuLoadPort])
  val loadPorts = ArrayBuffer[LoadPortSpec]()
  def newLoadPort(): Flow[LsuLoadPort] = {
    val physicalRdWidth = getService[DecoderService].PHYS_RD
    loadPorts.addRet(LoadPortSpec(Flow(LsuLoadPort(lqSize, wordWidth, widthOf(physicalRdWidth), virtualAddressWidth)))).port
  }


  override def wakeRobs    = List(logic.get.load.pipeline.hitSpeculation.wakeRob, logic.get.load.pipeline.ctrl.wakeRob, logic.get.special.wakeRob)
  override def wakeRegFile = List(logic.get.load.pipeline.hitSpeculation.wakeRf , logic.get.load.pipeline.ctrl.wakeRf , logic.get.special.wakeRf)
  def flushPort = setup.flushPort

  val keys = new AreaRoot{
    val SQ_ALLOC = Stageable(Bool())
    val LQ_ALLOC = Stageable(Bool())
    val LSU_ID = Stageable(UInt(log2Up(lqSize max sqSize) bits))
    val SIZE = Stageable(UInt(wordSizeWidth bits))
    val UNSIGNED = Stageable(Bool())
    val WRITE_RD = Stageable(Bool())
    val LQ_SEL = Stageable(UInt(log2Up(lqSize) bits))
    val LQ_SEL_OH = Stageable(Bits(lqSize bits))
    val SQ_SEL = Stageable(UInt(log2Up(sqSize) bits))
    val SQ_SEL_OH = Stageable(Bits(sqSize bits))
    val ADDRESS_PRE_TRANSLATION = Stageable(UInt(virtualAddressWidth bits))
    val DATA_MASK = Stageable(Bits(wordBytes bits))
  }
  import keys._

  val setup = create early new Area{
    val rob = getService[RobPlugin]
    val decoder = getService[DecoderService]
    val frontend = getService[FrontendPlugin]
    val cache = getService[DataCachePlugin]
    val regfile = getService[RegfileService]
    val commit = getService[CommitService]
    val translation = getService[AddressTranslationService]
    val doc = getService[DocPlugin]
    val dispatch = getService[DispatchPlugin]
    val fetch = getService[FetchPlugin]

    rob.retain()
    decoder.retain()
    frontend.retain()
    translation.retain()
    fetch.retain()

    getServiceOption[PrivilegedService].foreach(_.addMisa('A'))

    val amos = List(
      Rvi.AMOSWAP, Rvi.AMOADD, Rvi.AMOXOR, Rvi.AMOAND, Rvi.AMOOR,
      Rvi.AMOMIN, Rvi.AMOMAX, Rvi.AMOMINU, Rvi.AMOMAXU
    )
    amos.foreach(dispatch.fenceYounger(_))
    dispatch.fenceOlder  (Rvi.LR)
    dispatch.fenceYounger(Rvi.SC)
    dispatch.fenceOlder  (Rvi.SC)

    val rfWrite = regfile.newWrite(withReady = false, latency = 0)
    val cacheLoad = cache.newLoadPort(priority = 0)
    val cacheStore = cache.newStorePort()
    val specialCompletion = rob.newRobCompletion()
    val loadCompletion = rob.newRobCompletion()
    val storeCompletion = rob.newRobCompletion()
    val loadTrap = commit.newSchedulePort(canTrap = true, canJump = false)
    val storeTrap = commit.newSchedulePort(canTrap = true, canJump = true)
    val specialTrap = commit.newSchedulePort(canTrap = true, canJump = false)
    val flushPort = PulseHandshake().idle()

    decoder.addResourceDecoding(naxriscv.interfaces.LQ, LQ_ALLOC)
    decoder.addResourceDecoding(naxriscv.interfaces.SQ, SQ_ALLOC)
    doc.property("LSU_PERIPHERAL_WIDTH", wordWidth)

    val translationStorage = translation.newStorage(translationStorageParameter)
    val postCommitBusy = False
  }

  val logic = create late new Area{
    val rob = getService[RobPlugin]
    val decoder = getService[DecoderService]
    val frontend = getService[FrontendPlugin]
    val fetch = getService[FetchPlugin]
    val cache = getService[DataCachePlugin]
    val commit = getService[CommitService]
    val translationService = getService[AddressTranslationService]
    lock.await()

    val keysLocal = new AreaRoot {
      val LQ_ID = Stageable(UInt(log2Up(lqSize) bits))
      val SQ_ID = Stageable(UInt(log2Up(sqSize) bits))

      val LQ_ID_CARRY = Stageable(Bool())
      val SQ_ID_CARRY = Stageable(Bool())

      val YOUNGER_LOAD_PC         = Stageable(PC)
      val YOUNGER_LOAD_ROB        = Stageable(ROB.ID)
      val YOUNGER_LOAD_RESCHEDULE = Stageable(Bool())

      val HIT_SPECULATION = Stageable(Bool())
      val HIT_SPECULATION_COUNTER = Stageable(SInt(hitPredictionCounterWidth bits))
      val LOAD_FRESH      = Stageable(Bool())
      val LOAD_FRESH_PC   = Stageable(PC())
      val LOAD_FRESH_WAIT_SQ = Stageable(Bool())
      val LOAD_WRITE_FAILURE = Stageable(Bool())
      val LOAD_CACHE_RSP = Stageable(cloneOf(setup.cacheLoad.rsp))

      val OLDER_STORE_RESCHEDULE  = Stageable(Bool())
      val OLDER_STORE_ID = Stageable(SQ_ID)
      val OLDER_STORE_COMPLETED = Stageable(Bool()) //Used to avoid LQ waiting on SQ which just fired

      val LQCHECK_START_ID = Stageable(UInt(log2Up(lqSize) + 1 bits))
      val LQCHECK_HITS = Stageable(Bits(lqSize bits))
      val LQCHECK_NO_YOUNGER = Stageable(Bool())

      val SQCHECK_END_ID = Stageable(UInt(log2Up(sqSize) + 1 bits))
      val SQCHECK_HITS = Stageable(Bits(sqSize bits))
      val SQCHECK_NO_OLDER = Stageable(Bool())

      val AMO, LR, SC = Stageable(Bool())
      val MISS_ALIGNED = Stageable(Bool())
      val PAGE_FAULT = Stageable(Bool())
      val STORE_DO_TRAP = Stageable(Bool())
      val STORE_TRAP_PORT_ROB_ID = Stageable(ROB.ID)
    }
    import keysLocal._

    val cpuWordToRfWordRange = log2Up(wordBytes)-1 downto log2Up(wordBytes) //useless for now
    val memToCpuRange = log2Up(cache.memDataWidth/8)-1 downto log2Up(wordBytes)

    val rescheduling = commit.reschedulingPort

    val allocStage = frontend.pipeline.dispatch
    for(slotId <- 0 until Frontend.DISPATCH_COUNT){
      allocStage(LSU_ID, slotId).assignDontCare()
    }

    val lq = new Area{
      val regs = for(i <- 0 until lqSize) yield RegType(i)
      case class RegType(id : Int) extends Area{
        val valid = RegInit(False)
        val waitOn = new Area{
          val address     = Reg(Bool())
          val cacheRsp    = Reg(Bool())
          val cacheRefill = Reg(Bits(cache.refillCount bits))
          val cacheRefillAny = Reg(Bool())
          val sq     = Reg(Bool())
          val sqId   = Reg(SQ_ID)
          val sqPredicted = Reg(Bool())
          val commit = Reg(Bool())

          val cacheRefillSet = cacheRefill.getZero
          val cacheRefillAnySet = False

          cacheRefill := (cacheRefill | cacheRefillSet) & ~cache.refillCompletions
          cacheRefillAny := (cacheRefillAny | cacheRefillAnySet) & !cache.refillCompletions.orR

          val translationWakeAny = Reg(Bool)
          val translationWakeAnySet = False
          val translationWakeAnyClear = Bool()

          translationWakeAny := ((translationWakeAny & !translationWakeAnyClear) | translationWakeAnySet)
        }
        val address = new Area {
          val pageOffset = Reg(UInt(pageOffsetWidth bits))
          val size = Reg(SIZE())
          val unsigned = Reg(UNSIGNED())
          val mask = Reg(DATA_MASK)
        }

        val ready = valid && !waitOn.address && !waitOn.cacheRsp && waitOn.cacheRefill === 0 && !waitOn.cacheRefillAny && !waitOn.commit && !waitOn.sq && !waitOn.translationWakeAny
      }

      val mem = new Area{
        val addressPre = Mem.fill(lqSize)(UInt(virtualAddressWidth bits))
        val addressPost = Mem.fill(lqSize)(UInt(virtualAddressWidth bits))
        val physRd = Mem.fill(lqSize)(decoder.PHYS_RD)
        val robId = Mem.fill(lqSize)(ROB.ID)
        val pc = Mem.fill(lqSize)(PC)
        val sqAlloc = Mem.fill(lqSize)(UInt(log2Up(sqSize)+1 bits))
        val io = Mem.fill(lqSize)(Bool())
        val writeRd = Mem.fill(lqSize)(Bool())
        val lr = Mem.fill(lqSize)(Bool())
      }

      val reservation = new Area{
        val valid = Reg(Bool()) init(False)
        val address = Reg(UInt(postWidth bits))
      }

      val hazardPrediction = new Area{
        def hash(pc : UInt)  : Bits = pc(Fetch.SLICE_RANGE_LOW + log2Up(hazardPedictionEntries), hazardPredictionTagWidth bits).asBits
        def index(pc : UInt) : UInt = pc(Fetch.SLICE_RANGE_LOW, log2Up(hazardPedictionEntries) bits)
        case class HazardPredictionEntry() extends Bundle {
          val tag = Bits(hazardPredictionTagWidth bits)
        }
        val mem = Mem.fill(hazardPedictionEntries)(HazardPredictionEntry())
      }

      val hitPrediction = new Area {
        def index(pc: UInt): UInt = pc(Fetch.SLICE_RANGE_LOW, log2Up(hitPedictionEntries) bits)

        case class HitPredictionEntry() extends Bundle {
          val counter = SInt(hitPredictionCounterWidth bits)
        }

        val mem = Mem.fill(hitPedictionEntries)(HitPredictionEntry())
        val writePort = mem.writePort
        val writeLast = writePort.stage()
      }

      val ptr = new Area{
        val alloc, free = Reg(UInt(log2Up(lqSize) + 1 bits)) init (0)
        val allocReal = U(alloc.dropHigh(1))
        val freeReal = U(free.dropHigh(1))
        def isFull(ptr : UInt) = (ptr ^ free) === lqSize
        val priority = Reg(Bits(lqSize-1 bits)) init(0) //TODO check it work properly
      }
    }

    val sq = new Area{
      val regs = for(i <- 0 until sqSize) yield RegType(i)
      case class RegType(id : Int) extends Area{
        val valid = RegInit(False)
        val commited = RegInit(False) //Warning, commited is meaning full even when valid == False !
        val commitedNext = CombInit(commited)
        commited := commitedNext

        val address = new Area{
          val pageOffset  = Reg(UInt(pageOffsetWidth bits))
          val size = Reg(SIZE())
          val mask = Reg(DATA_MASK)
        }

        val waitOn = new Area {
          val address = Reg(Bool())
          val translationRsp  = Reg(Bool())
          val translationWakeAny = Reg(Bool)
          val writeback  = Reg(Bool())
          val translationWakeAnySet = False
          val translationWakeAnyClear = Bool()

          translationWakeAny := (translationWakeAny | translationWakeAnySet) & !translationWakeAnyClear
        }

        val allLqIsYounger = Reg(Bool())

        val ready = valid && !waitOn.address && !waitOn.translationRsp && !waitOn.writeback && !waitOn.translationWakeAny
      }

      val mem = new Area{
        val addressPre = Mem.fill(sqSize)(UInt(virtualAddressWidth bits))
        val addressPost = Mem.fill(sqSize)(UInt(virtualAddressWidth bits))
        val word = Mem.fill(sqSize)(Bits(wordWidth bits))
        val robId = Mem.fill(sqSize)(ROB.ID)
        val lqAlloc = Mem.fill(sqSize)(UInt(log2Up(lqSize) + 1 bits))
        val io = Mem.fill(sqSize)(Bool())
        val amo = Mem.fill(sqSize)(Bool())
        val sc = Mem.fill(sqSize)(Bool())
        //Only one AMO/SC can be schedule at once, so we can store things in simple registers
        val swap = Reg(Bool())
        val op  = Reg(Bits(3 bits))
        val physRd = Reg(decoder.PHYS_RD)
        val writeRd = Reg(Bool())
      }

      val ptr = new Area{
        val alloc, commit, writeBack, free = Reg(UInt(log2Up(sqSize) + 1 bits)) init (0)
        val allocReal = U(alloc.dropHigh(1))
        val freeReal = U(free.dropHigh(1))
        val writeBackReal = U(writeBack.dropHigh(1))
        val commitReal = U(commit.dropHigh(1))
        val commitNext = cloneOf(commit)
        commit := commitNext
        val priority = Reg(Bits(sqSize-1 bits)) init(0) //TODO check it work properly
        def isFull(ptr : UInt) = (ptr ^ free) === sqSize
        def isFree(ptr : UInt) = (free - ptr) < sqSize

        val onFree = Flow(UInt(log2Up(sqSize) bits))
        val onFreeLast = onFree.stage()

        setup.postCommitBusy setWhen(commit =/= free)
      }
    }

    val load = new Area{
      import lq._

      for(reg <- regs) when(sq.ptr.onFree.valid && sq.ptr.onFree.payload === reg.waitOn.sqId){
        reg.waitOn.sq := False
      }

      val push = for(s <- loadPorts) yield new Area{
        val spec = s
        import spec._
        def writeMem[T <: Data](mem : Mem[T], value : T) = {
          mem.write(
            enable = port.valid,
            address = port.lqId,
            data = value
          )
        }
        writeMem(mem.addressPre, port.address)
        writeMem(mem.physRd, port.physicalRd)
        writeMem(mem.robId, port.robId)
        writeMem(mem.pc, port.pc)
        writeMem(mem.writeRd, port.writeRd)
        writeMem(mem.lr, port.lr)

        val hazardPrediction = new Area{
          val read = lq.hazardPrediction.mem.readSyncPort
          read.cmd.valid := port.earlySample
          read.cmd.payload := lq.hazardPrediction.index(port.earlyPc)

          val hash = lq.hazardPrediction.hash(port.pc)
          val hit = read.rsp.tag === hash
          val sqAlloc = mem.sqAlloc.readAsync(port.lqId)
          val sqId = (sqAlloc - 1).resize(log2Up(sqSize))
          val freeAlready  = sq.ptr.isFree(sqAlloc)
          val freeDetected = sq.ptr.onFree.valid && sq.ptr.onFree.payload === sqId.resized
          val waitSq = hit && !freeDetected && !freeAlready
        }

        val hitPrediction = new Area{
          val read = lq.hitPrediction.mem.readSyncPort
          read.cmd.valid := port.earlySample
          read.cmd.payload := lq.hitPrediction.index(port.earlyPc)


          def write = lq.hitPrediction.writePort

          val bypassHit = RegNext(read.cmd.payload === write.address && write.valid)
          val bypassData = RegNext(write.data)
          when(bypassHit){
            read.rsp := bypassData
          }

          val likelyToHit = read.rsp.counter.msb
        }

        val oh = UIntToOh(port.lqId)
        when(port.valid) {
          for (entry <- regs) when(oh(entry.id)) {
            entry.waitOn.address := False
            entry.address.pageOffset := port.address(pageOffsetRange)
            entry.address.size := port.size
            entry.address.unsigned := port.unsigned
            entry.address.mask := AddressToMask(port.address, port.size, wordBytes)
            entry.waitOn.sq   := hazardPrediction.waitSq
            entry.waitOn.sqId := hazardPrediction.sqId
            entry.waitOn.sqPredicted := hazardPrediction.waitSq
          }
        }
      }

      val allocate = new Area{
        import allocStage._

        val full = False
        haltIt(isValid && full)

        var alloc = CombInit(ptr.alloc)
        for(slotId <- 0 until Frontend.DISPATCH_COUNT){
          implicit val _ = StageableOffset(slotId)
          LQ_ID := alloc.resized
          LQ_ID_CARRY := alloc.msb
          when(DISPATCH_MASK && LQ_ALLOC){
            LSU_ID := alloc.resized
            when(isFireing) {
              mem.sqAlloc.write(
                address = LQ_ID,
                data = U(SQ_ID_CARRY ## allocStage(SQ_ID, slotId))
              )
            }
            when(ptr.isFull(alloc)){
              full := True
            }
            alloc \= alloc + 1
          }
        }


        when(isFireing){
          ptr.alloc := alloc
          for(reg <- regs){
            val hits = for(slotId <- 0 until Frontend.DISPATCH_COUNT) yield{
              (DISPATCH_MASK, slotId) && (LQ_ALLOC, slotId) && (LSU_ID, slotId).resize(log2Up(lqSize) bits) === reg.id
            }
            when(hits.orR){
              reg.valid := True
              reg.waitOn.address := True
              reg.waitOn.cacheRsp := False
              reg.waitOn.cacheRefill := 0
              reg.waitOn.cacheRefillAny := False
              reg.waitOn.commit := False
              reg.waitOn.translationWakeAny := False
            }
          }
        }
      }

      val pipeline = new Pipeline{
        val stages = Array.fill(loadCtrlAt+1)(newStage())
        connect(stages)(List(M2S()))

        assert(loadFeedAt + cache.loadRspLatency <= loadCtrlAt, "Cache response is comming after lsu loadCtrlAt")

        stages.last.flushIt(rescheduling.valid, root = false)

        val translationPort = translationService.newTranslationPort(
          stages = this.stages,
          preAddress = ADDRESS_PRE_TRANSLATION,
          usage = LOAD_STORE,
          portSpec = loadTranslationParameter,
          storageSpec = setup.translationStorage
        )
        val tpk = translationPort.keys
        lq.regs.foreach(_.waitOn.translationWakeAnyClear := translationPort.wake)

        val feed = new Area{
          val stage = stages(0)
          import stage._

          //Get the oldest load in the LQ ready for the cache access
          val arbitration = new Area{
            val hits = B(regs.map(reg => reg.ready))
            val hit = hits.orR

            case class Payload() extends Bundle{
              val selOh = Bits(lqSize bits)
              val sel   = UInt(log2Up(lqSize) bits)
            }
            val selOh = OHMasking.roundRobinMasked(hits, ptr.priority)
            val early = Stream(Payload())
            early.valid := hit
            early.selOh := selOh
            early.sel   := OHToUInt(selOh)

            for(reg <- regs) when(early.fire && selOh(reg.id)){
              reg.waitOn.cacheRsp := True
            }

            val output = if(lqToCachePipelined) early.m2sPipe(flush = stages.head.isFlushed) else early.combStage()
            def outputSel = output.sel //OHToUInt(output.payload)
          }

          isValid := arbitration.output.valid
          LQ_SEL_OH := arbitration.output.selOh
          LQ_SEL := arbitration.outputSel
          ADDRESS_PRE_TRANSLATION := mem.addressPre.readAsync(arbitration.outputSel)
          SIZE := regs.map(_.address.size).read(arbitration.outputSel)
          arbitration.output.ready := isReady
          ROB.ID          := mem.robId.readAsync(LQ_SEL)
          decoder.PHYS_RD := mem.physRd.readAsync(LQ_SEL)
          LOAD_FRESH := False
          LOAD_FRESH_WAIT_SQ := False
          HIT_SPECULATION := False //TODO maybe True by default for better perf ?

          val loadBypass = if(loadToCacheBypass) new Area {
            assert(loadPorts.size == 1, "Not suported yet")
            val port = loadPorts.head.port
            val portPush = push.head
            isValid setWhen(port.valid) // && !portPush.hazardPrediction.waitSq   removed to help timings
            when(!arbitration.output.valid){
              LQ_SEL                  := port.lqId
              LQ_SEL_OH               := portPush.oh
              ADDRESS_PRE_TRANSLATION := port.address
              SIZE                    := port.size
              ROB.ID                  := port.robId
              decoder.PHYS_RD         := port.physicalRd
              LOAD_FRESH := True
              LOAD_FRESH_WAIT_SQ := portPush.hazardPrediction.waitSq

              HIT_SPECULATION := portPush.hitPrediction.likelyToHit
              when(port.valid && isFireing){
                for(reg <- regs) when(portPush.oh(reg.id)){
                  reg.waitOn.cacheRsp := True
                }
              }
            }

            LOAD_FRESH_PC := portPush.spec.port.pc
            HIT_SPECULATION_COUNTER := portPush.hitPrediction.read.rsp.counter
          }

          DATA_MASK       := AddressToMask(ADDRESS_PRE_TRANSLATION, SIZE, wordBytes)
          SQCHECK_END_ID  := mem.sqAlloc.readAsync(LQ_SEL)
        }

        val feedCache = new Area{
          val stage = stages(loadFeedAt)
          import stage._

          val cmd = setup.cacheLoad.cmd //If you move that in another stage, be carefull to update loadFeedAt usages (sq d$ writeback rsp delay)
          cmd.valid := stage.isValid
          cmd.virtual := ADDRESS_PRE_TRANSLATION
          cmd.size := SIZE
          cmd.redoOnDataHazard := False

          haltIt(!cmd.ready)
        }

        val feedContext = new Area {
          val stage = stages(1)
          import stage._

          WRITE_RD        := mem.writeRd.readAsync(LQ_SEL)
          LR              := mem.lr.readAsync(LQ_SEL)
          UNSIGNED        := regs.map(_.address.unsigned).read(LQ_SEL)
        }

        val feedTranslation = new Area{
          val stage = stages(loadFeedAt + setup.cacheLoad.translatedAt)
          import stage._

          setup.cacheLoad.translated.physical := tpk.TRANSLATED
          setup.cacheLoad.translated.abord := stage(tpk.IO) || tpk.PAGE_FAULT || !tpk.ALLOW_READ || tpk.REDO
        }

        val cancels = for(stageId <- 0 to cache.loadRspLatency){
          setup.cacheLoad.cancels(stageId) := stages(loadFeedAt + stageId).isFireing && rescheduling.valid
        }

        val checkSqMask = new Area{
          val stage = stages(loadCheckSqAt) //WARNING, SQ delay between writeback and entry.valid := False should not be smaller than the delay of reading the cache and checkSq !!
          import stage._

          val startId = CombInit(sq.ptr.free)
          val startMask = U(UIntToOh(U(startId.dropHigh(1))))-1
          val endMask   = U(UIntToOh(U(SQCHECK_END_ID.dropHigh(1))))-1
          val loopback = endMask <= startMask
          val youngerMask = loopback ? ~(endMask ^ startMask) otherwise (endMask & ~startMask)
          val olderMaskEmpty = startId === SQCHECK_END_ID

          val hits = Bits(sqSize bits)
          val entries = for(sqReg <- sq.regs) yield new Area {
            val pageHit = sqReg.address.pageOffset === ADDRESS_PRE_TRANSLATION(pageOffsetRange)
            val wordHit = (sqReg.address.mask & DATA_MASK) =/= 0
            hits(sqReg.id) := sqReg.valid && !sqReg.waitOn.address && pageHit && wordHit && youngerMask(sqReg.id)
          }

          SQCHECK_HITS := hits
          SQCHECK_NO_OLDER := olderMaskEmpty
        }

        val checkSqArbi = new Area{
          val stage = stages(loadCheckSqAt + 1) //Warning, if you remove the +1 remove some of the OLDER_STORE_COMPLETED bypass
          import stage._

          val olderHit = !SQCHECK_NO_OLDER && SQCHECK_HITS =/= 0
          val olderOh   = if(sqSize == 1) B(1) else OHMasking.roundRobinMaskedFull(SQCHECK_HITS.reversed, ~((sq.ptr.priority ## !sq.ptr.priority.msb).reversed)).reversed //reverted priority, imprecise would be ok
          val olderSel  = OHToUInt(olderOh)

          OLDER_STORE_RESCHEDULE := olderHit
          OLDER_STORE_ID := olderSel
          OLDER_STORE_COMPLETED := sq.ptr.onFreeLast.valid && sq.ptr.onFreeLast.payload === OLDER_STORE_ID
          for(s <- stages.dropWhile(_ != stage)){
            s.overloaded(OLDER_STORE_COMPLETED) := s(OLDER_STORE_COMPLETED) || sq.ptr.onFree.valid && sq.ptr.onFree.payload === s(OLDER_STORE_ID)
          }
        }


        val hitSpeculation = new Area{
          val stage = stages(loadFeedAt + cache.loadRspLatency - 2)
          import stage._

          val wakeRob = Flow(WakeRob())
          wakeRob.valid := isFireing && HIT_SPECULATION
          wakeRob.robId := ROB.ID

          val wakeRf = Flow(WakeRegFile(decoder.PHYS_RD, needBypass = true))
          wakeRf.valid := isFireing && HIT_SPECULATION
          wakeRf.physical := decoder.PHYS_RD
        }

        val cacheRsp = new Area {
          val stage = stages(loadFeedAt + cache.loadRspLatency)
          import stage._

          def rsp = stage(LOAD_CACHE_RSP)
          rsp := setup.cacheLoad.rsp

          val rspSize    = CombInit(stage(SIZE))
          val rspAddress = CombInit(stage(ADDRESS_PRE_TRANSLATION))
          val rspRaw     = rsp.data //CombInit(rsp.data.subdivideIn(wordWidth bits).read(ADDRESS_PRE_TRANSLATION(memToCpuRange)))
          val rspSplits  = rspRaw.subdivideIn(8 bits)
          val rspShifted = Bits(wordWidth bits)

          //Generate minimal mux to move from a wide aligned memory read to the register file shifter representation
          for(i <- 0 until wordBytes){
            val srcSize = 1 << (log2Up(wordBytes) - log2Up(i+1))
            val srcZipped = rspSplits.zipWithIndex.filter{case (v, b) => b % (wordBytes/srcSize) == i}
            val src = srcZipped.map(_._1)
            val range = cpuWordToRfWordRange.high downto cpuWordToRfWordRange.high+1-log2Up(srcSize)
            val sel = rspAddress(range)
            //        println(s"$i $srcSize $range ${srcZipped.map(_._2).mkString(",")}")
            rspShifted(i*8, 8 bits) := src.read(sel)
          }

          assert(Global.XLEN.get == 32)
          val rspFormated = rspSize.mux(
            0 -> B((31 downto 8) -> (rspShifted(7) && !UNSIGNED),(7 downto 0) -> rspShifted(7 downto 0)),
            1 -> B((31 downto 16) -> (rspShifted(15) && !UNSIGNED),(15 downto 0) -> rspShifted(15 downto 0)),
            default -> rspShifted //W
          )

          setup.rfWrite.valid   := isValid && WRITE_RD
          setup.rfWrite.address := decoder.PHYS_RD
          setup.rfWrite.data    := rspFormated
          setup.rfWrite.robId   := ROB.ID

          val peripheralOverride = False //Allow the peripheral ctrl to cannibalise this data path logic <3
          LOAD_WRITE_FAILURE := peripheralOverride && !tpk.IO
        }

        //Bypass load rsp refillSlotxxx
        for(stageId <- loadFeedAt + cache.loadRspLatency until loadCtrlAt){
          val stage = stages(stageId)
          def o = stage.overloaded(LOAD_CACHE_RSP)
          def i = stage(LOAD_CACHE_RSP)
          o := i
          o.refillSlotAny.removeAssignments() := i.refillSlotAny && !cache.refillCompletions.orR
          o.refillSlot.removeAssignments()    := i.refillSlot     & ~cache.refillCompletions
        }

        val ctrl = new Area{
          val stage = stages(loadCtrlAt)
          import stage._

          def rsp = stage(LOAD_CACHE_RSP)

          setup.loadCompletion.valid := False
          setup.loadCompletion.id := ROB.ID

          val wakeRob = Flow(WakeRob())
          wakeRob.valid := False
          wakeRob.robId := ROB.ID

          val wakeRf = Flow(WakeRegFile(decoder.PHYS_RD, needBypass = false))
          wakeRf.valid := False
          wakeRf.physical := decoder.PHYS_RD

          setup.loadTrap.valid      := False
          setup.loadTrap.robId      := ROB.ID
          setup.loadTrap.tval       := B(ADDRESS_PRE_TRANSLATION)
          setup.loadTrap.skipCommit := True
          setup.loadTrap.cause      := EnvCallPlugin.CAUSE_REDO
          setup.loadTrap.reason     := ScheduleReason.LOAD_HIT_MISS_PREDICTED

          val missAligned = (1 to log2Up(wordWidth/8)).map(i => SIZE === i && ADDRESS_PRE_TRANSLATION(i-1 downto 0) =/= 0).orR
          val pageFault = !tpk.ALLOW_READ || tpk.PAGE_FAULT
          val accessFault = CombInit(rsp.fault)


          def onRegs(body : RegType => Unit) = for(reg <- regs) when(LQ_SEL_OH(reg.id)){ body(reg) }

          val hitSpeculationTrap = True
          when(isFireing) {
            setup.loadTrap.valid := HIT_SPECULATION && hitSpeculationTrap
            mem.addressPost.write(
              address = LQ_SEL,
              data   = tpk.TRANSLATED
            )
            mem.io.write(
              address = LQ_SEL,
              data   = tpk.IO
            )
            onRegs(_.waitOn.cacheRsp := False)
            when(LOAD_FRESH_WAIT_SQ || LOAD_WRITE_FAILURE){

            } elsewhen(stage(OLDER_STORE_RESCHEDULE)){
              onRegs{r =>
                r.waitOn.sq setWhen(!stage.resulting(OLDER_STORE_COMPLETED))
                r.waitOn.sqId := OLDER_STORE_ID
              }
            } elsewhen(stage(tpk.REDO)){
              onRegs(_.waitOn.translationWakeAnySet := True)
            } elsewhen(rsp.redo) {
              when(rsp.refillSlotAny) {
                onRegs(_.waitOn.cacheRefillAnySet := True)
              } otherwise {
                onRegs(_.waitOn.cacheRefillSet := rsp.refillSlot)
              }
            } elsewhen(missAligned) {
              setup.loadTrap.valid := True
              setup.loadTrap.cause := CSR.MCAUSE_ENUM.LOAD_MISALIGNED
              setup.loadTrap.reason := ScheduleReason.TRAP
            } elsewhen(pageFault) {
              setup.loadTrap.valid := True
              setup.loadTrap.reason := ScheduleReason.TRAP
              setup.loadTrap.cause := CSR.MCAUSE_ENUM.LOAD_PAGE_FAULT
            } elsewhen(accessFault) {
              setup.loadTrap.valid := True
              setup.loadTrap.cause := CSR.MCAUSE_ENUM.LOAD_ACCESS_FAULT
              setup.loadTrap.reason := ScheduleReason.TRAP
            } otherwise {
              onRegs(_.waitOn.commit := True)
              //doCompletion
            }
          }

          //Critical path extracted to help synthesis
          val doCompletion = isFireing && !LOAD_FRESH_WAIT_SQ && !LOAD_WRITE_FAILURE && !OLDER_STORE_RESCHEDULE && !missAligned && !pageFault && !pageFault && !accessFault && !tpk.IO
          KeepAttribute(doCompletion)
          val success = doCompletion && !rsp.redo
          when(success){
            hitSpeculationTrap := False
            setup.loadCompletion.valid := True
            when(LR){
              lq.reservation.valid   := True
              lq.reservation.address := tpk.TRANSLATED
            }
            when(WRITE_RD && !HIT_SPECULATION) {
              wakeRob.valid := True
              wakeRf.valid := True
            }
          }



          val hitPrediction = new Area{
            def onSuccess = S(-1)
            def onFailure = S(hitPredictionErrorPenality)
            val next = HIT_SPECULATION_COUNTER +^ (success ? onSuccess | onFailure)

            val writePort = lq.hitPrediction.writePort
            writePort.valid    := isFireing && LOAD_FRESH
            writePort.address  := lq.hitPrediction.index(LOAD_FRESH_PC)
            writePort.data.counter := next.sat(widthOf(next) - hitPredictionCounterWidth bits)
            when(!tpk.REDO && !tpk.PAGE_FAULT && tpk.IO && tpk.ALLOW_READ){
              writePort.data.counter := writePort.data.counter.maxValue
            }
          }
        }
      }


      val onCommit = new Area{
        val event = commit.onCommit()
        val lqAlloc = rob.readAsync(LQ_ALLOC, Global.COMMIT_COUNT, event.robId)
        val lqCommits = (0 until Global.COMMIT_COUNT).map(slotId => event.mask(slotId) && lqAlloc(slotId))
        var free = CombInit(ptr.free)
        var priority = CombInit(ptr.priority)
        for(inc <- lqCommits){
          for(reg <- regs) when(free.resize(log2Up(lqSize)) === reg.id && inc){
            reg.valid := False
          }
          when(inc) {
            priority \= (priority === 0) ? B(widthOf(priority) bits, default -> true).resized | (priority |<< 1)
          }
          free \= free + U(inc)
        }
        ptr.priority := priority
        ptr.free := free
      }
      Handle{
        pipeline.translationPort.pipelineLock.await()
        pipeline.build()
      }
    }


    val store = new Area{
      import sq._
      for(spec <- storePorts){
        import spec._

        def writeMem[T <: Data](mem : Mem[T], value : T) = {
          mem.write(
            enable = port.valid,
            address = port.sqId,
            data = value
          )
        }

        writeMem(mem.addressPre, port.address)
        writeMem(mem.word, port.data)
        writeMem(mem.robId, port.robId)
        writeMem(mem.amo, port.amo)
        writeMem(mem.sc, port.sc)
        when(port.fire && (port.sc || port.amo)){
          mem.swap    := port.swap
          mem.op      := port.op
          mem.physRd  := port.physicalRd
          mem.writeRd := port.writeRd
        }

        when(port.valid) {
          for (entry <- regs) when(port.sqId === entry.id) {
            entry.waitOn.address := False
            entry.address.pageOffset := port.address(pageOffsetRange)
            entry.address.size := port.size
            entry.address.mask := AddressToMask(port.address, port.size, wordBytes)
          }
        }
      }

      val allocate = new Area{
        import allocStage._

        val full = False
        haltIt(isValid && full)

        var alloc = CombInit(ptr.alloc)

        for(slotId <- 0 until Frontend.DISPATCH_COUNT){
          implicit val _ = StageableOffset(slotId)
          SQ_ID := alloc.resized
          SQ_ID_CARRY := alloc.msb
          when(DISPATCH_MASK && SQ_ALLOC){
            LSU_ID := alloc.resized
            when(isFireing) {
              mem.lqAlloc.write(
                address = SQ_ID,
                data = U(LQ_ID_CARRY ## allocStage(LQ_ID, slotId))
              )
            }
            when(ptr.isFull(alloc)){
              full := True
            }
            alloc \= alloc + 1
          }
        }

        when(isFireing){
          ptr.alloc := alloc
          for(reg <- regs){
            val hits = for(slotId <- 0 until Frontend.DISPATCH_COUNT) yield{
              (DISPATCH_MASK, slotId) && (SQ_ALLOC, slotId) && (LSU_ID, slotId).resize(log2Up(sqSize) bits) === reg.id
            }
            when(hits.orR){
              reg.valid := True
              reg.allLqIsYounger := False
              reg.waitOn.address := True
              reg.waitOn.translationRsp := False
              reg.waitOn.writeback := False
              reg.waitOn.translationWakeAny := False
            }
          }
        }
      }

      val pipeline = new Pipeline {
        val stages = Array.fill(4)(newStage()) //TODO
        connect(stages)(List(M2S()))

        stages.last.flushIt(rescheduling.valid, root = false)

        val translationPort = translationService.newTranslationPort(
          stages = this.stages,
          preAddress = ADDRESS_PRE_TRANSLATION,
          usage = LOAD_STORE,
          portSpec = storeTranslationParameter,
          storageSpec = setup.translationStorage
        )
        val tpk = translationPort.keys

        sq.regs.foreach(_.waitOn.translationWakeAnyClear := translationPort.wake)


        val feed = new Area{
          val stage = stages(0)
          import stage._

          val hits = B(regs.map(_.ready))
          val hit = hits.orR
          val selOh = OHMasking.roundRobinMasked(hits, ptr.priority)
          val sel = OHToUInt(selOh)

          for(reg <- regs) when(selOh(reg.id)){
            reg.waitOn.translationRsp := True
          }

          isValid := hit
          SQ_SEL := sel
          SQ_SEL_OH := selOh
          ROB.ID := mem.robId.readAsync(sel)
          ADDRESS_PRE_TRANSLATION := mem.addressPre.readAsync(sel)
          AMO := mem.amo.readAsync(sel)
          SC := mem.sc.readAsync(sel)
          SIZE := regs.map(_.address.size).read(sel)
          DATA_MASK := AddressToMask(ADDRESS_PRE_TRANSLATION, SIZE, wordBytes)
          LQCHECK_START_ID := mem.lqAlloc.readAsync(sel)
        }

        val misc = new Area {
          val stage = stages(1)
          import stage._

          MISS_ALIGNED := (1 to log2Up(wordWidth/8)).map(i => SIZE === i && ADDRESS_PRE_TRANSLATION(i-1 downto 0) =/= 0).orR
        }

        //TODO timings
        val checkLqHits = new Area{
          val stage = stages(1)
          import stage._

          val endId = CombInit(lq.ptr.alloc)
          val startMask = U(UIntToOh(U(LQCHECK_START_ID.dropHigh(1))))-1
          val endMask   = U(UIntToOh(U(endId.dropHigh(1))))-1
          val loopback = endMask <= startMask
          val youngerMask = loopback ? ~(endMask ^ startMask) otherwise (endMask & ~startMask)
          val youngerMaskEmpty = LQCHECK_START_ID === endId
          val allLqIsYounger = regs.map(_.allLqIsYounger).read(SQ_SEL)

          val entries = for(lqReg <- lq.regs) yield new Area {
            val pageHit = lqReg.address.pageOffset === ADDRESS_PRE_TRANSLATION(pageOffsetRange)
            val wordHit = (lqReg.address.mask & DATA_MASK) =/= 0
            val sqWaited = lqReg.waitOn.sq && lqReg.waitOn.sqId === SQ_SEL || lqReg.waitOn.sqPredicted
            LQCHECK_HITS(lqReg.id) := lqReg.valid && !lqReg.waitOn.address && !sqWaited && pageHit && wordHit && (youngerMask(lqReg.id) || allLqIsYounger)
          }

          LQCHECK_NO_YOUNGER := youngerMaskEmpty
        }

        val checkLqPrio = new Area{
          val stage = stages(2)
          import stage._

          val youngerHit  = LQCHECK_HITS =/= 0 && !LQCHECK_NO_YOUNGER
          val youngerOh   = OHMasking.roundRobinMasked(stage(LQCHECK_HITS), lq.ptr.priority)
          val youngerSel  = OHToUInt(youngerOh)

          YOUNGER_LOAD_PC := lq.mem.pc(youngerSel)
          YOUNGER_LOAD_ROB := lq.mem.robId.readAsync(youngerSel)
          YOUNGER_LOAD_RESCHEDULE := youngerHit

          //          YOUNGER_LOAD_PC := 0
          //          YOUNGER_LOAD_ROB := 0
          //          YOUNGER_LOAD_RESCHEDULE := False
        }

        val preCompletion = new Area {
          val stage = stages(2)
          import stage._

          PAGE_FAULT := !tpk.ALLOW_WRITE || tpk.PAGE_FAULT //Assumed always ok -> AMO && !tpk.ALLOW_READ
          STORE_DO_TRAP := MISS_ALIGNED || PAGE_FAULT
          STORE_TRAP_PORT_ROB_ID := (STORE_DO_TRAP ?  stage(ROB.ID) | stage(YOUNGER_LOAD_ROB))
        }

        val completion = new Area{
          val stage = stages(3)
          import stage._

          setup.storeCompletion.valid := False
          setup.storeCompletion.id := ROB.ID

          val lqPredictionPort = lq.hazardPrediction.mem.writePort
          lqPredictionPort.valid    := isFireing && YOUNGER_LOAD_RESCHEDULE
          lqPredictionPort.address  := lq.hazardPrediction.index(YOUNGER_LOAD_PC)
          lqPredictionPort.data.tag := lq.hazardPrediction.hash(YOUNGER_LOAD_PC)

          setup.storeTrap.robId    := STORE_TRAP_PORT_ROB_ID
          when(STORE_DO_TRAP) {
            setup.storeTrap.valid      := False
            setup.storeTrap.trap       := True
            setup.storeTrap.reason     := ScheduleReason.TRAP
          } otherwise {
            setup.storeTrap.valid    := isFireing && YOUNGER_LOAD_RESCHEDULE
            setup.storeTrap.trap     := False
            setup.storeTrap.reason   := ScheduleReason.STORE_TO_LOAD_HAZARD
          }
          setup.storeTrap.tval       := B(ADDRESS_PRE_TRANSLATION)
          setup.storeTrap.skipCommit := True
          setup.storeTrap.cause.assignDontCare()
          setup.storeTrap.pcTarget   := YOUNGER_LOAD_PC


          def onRegs(body : RegType => Unit) = for(reg <- regs) when(SQ_SEL_OH(reg.id)){ body(reg) }
          when(isFireing) {
            onRegs(_.waitOn.translationRsp := False)

            mem.addressPost.write(
              address = SQ_SEL,
              data   = tpk.TRANSLATED
            )
            mem.io.write(
              address = SQ_SEL,
              data   = tpk.IO
            )

            when(tpk.REDO){
              whenMasked(regs, SQ_SEL_OH){reg =>
                reg.waitOn.translationWakeAnySet := True
              }
            } elsewhen(stage(MISS_ALIGNED)) {
              setup.storeTrap.valid      := True
              setup.storeTrap.cause      := CSR.MCAUSE_ENUM.STORE_MISALIGNED
            } elsewhen(stage(PAGE_FAULT)) {
              setup.storeTrap.valid      := True
              setup.storeTrap.cause      := CSR.MCAUSE_ENUM.STORE_PAGE_FAULT
            } otherwise {
              onRegs(_.waitOn.writeback := True)
              when(!tpk.IO && !AMO && !SC) {
                setup.storeCompletion.valid := True
              }
            }
          }
        }

        Handle{
          translationPort.pipelineLock.await()
          build()
        }
      }

      val onCommit = new Area{
        val event = commit.onCommit()
        val sqAlloc = rob.readAsync(SQ_ALLOC, Global.COMMIT_COUNT, event.robId)
        val sqCommits = (0 until Global.COMMIT_COUNT).map(slotId => U(event.mask(slotId) && sqAlloc(slotId)))
        var commitComb = CombInit(ptr.commit)
        for(slotId <- 0 until Global.COMMIT_COUNT){
          val doit = event.mask(slotId) && sqAlloc(slotId)
          when(doit) { regs.map(_.commitedNext).write(U(commitComb.dropHigh(1)), True) } //TODO this is kind of a long combinatorial path (commit -> adder -> reg set)
          commitComb \= commitComb + U(doit)
        }
        ptr.commitNext := commitComb
      }

      val writeback = new Area{
        val generation = RegInit(False)
        //        val pipeline = new Pipeline {
        //          val stages = Array.fill(3)(newStage()) //TODO
        //          connect(stages)(List(M2S()))
        //        }

        val waitOn = new Area{
          val refillSlot    = Reg(Bits(cache.refillCount bits)) init(0) //Zero when refillSlotAny
          val refillSlotAny = Reg(Bool()) init(False)

          val ready = refillSlot === 0 && !refillSlotAny

          val refillSlotSet = refillSlot.getZero
          val refillSlotAnySet = False

          refillSlot := (refillSlot | refillSlotSet) & ~cache.refillCompletions
          refillSlotAny := (refillSlotAny | refillSlotAnySet) & !cache.refillCompletions.orR
        }

        val feed = new Area{
          //WARNING, setupCacheStore is also used by the peripheral controller to know what to do
          val io = sq.mem.io.readAsync(ptr.writeBackReal)
          val size = regs.map(_.address.size).read(ptr.writeBackReal)
          val data = mem.word.readAsync(ptr.writeBackReal)
          val skip = False //Used for store conditional
          val doit = ptr.writeBack =/= ptr.commit && waitOn.ready
          val fire = CombInit(doit)

          setup.cacheStore.cmd.valid := doit
          setup.cacheStore.cmd.address := mem.addressPost.readAsync(ptr.writeBackReal)
          setup.cacheStore.cmd.mask :=  AddressToMask(setup.cacheStore.cmd.address, size, widthOf(setup.cacheStore.cmd.mask))
          setup.cacheStore.cmd.generation := generation
          setup.cacheStore.cmd.data.assignDontCare()
          setup.cacheStore.cmd.io := io
          switch(size){
            for(s <- 0 to log2Up(widthOf(setup.cacheStore.cmd.data)/8)) is(s){
              val w = (1 << s)*8
              setup.cacheStore.cmd.data.subdivideIn(w bits).foreach(_ := data(0, w bits))
            }
          }

          ptr.writeBack := ptr.writeBack + U(fire)
        }

        val rsp = new Area{
          val hazardFreeDelay = loadCheckSqAt - (loadFeedAt + cache.loadCmdHazardFreeLatency) + cache.storeRspHazardFreeLatency - 1 // -1 because sq regs update is sequancial
          val delayed = Vec.fill((hazardFreeDelay + 1) max 0)(cloneOf(setup.cacheStore.rsp))
          delayed.head << setup.cacheStore.rsp

          for((m,s) <- (delayed, delayed.tail).zipped) {
            val patched = m.combStage()
            s << patched.stage()
            patched.refillSlot.removeAssignments()
            patched.refillSlotAny.removeAssignments()
            patched.refillSlot  := m.refillSlot & ~cache.refillCompletions
            patched.refillSlotAny := m.refillSlotAny && cache.refillCompletions === 0
          }


          sq.ptr.onFree.valid := False
          sq.ptr.onFree.payload := ptr.freeReal

          when(delayed.last.valid && !delayed.last.generationKo && !delayed.last.flush){
            when(delayed.last.redo) {
              waitOn.refillSlotSet := delayed.last.refillSlot
              waitOn.refillSlotAnySet := delayed.last.refillSlotAny
              generation := !generation
              ptr.writeBack := ptr.free
            } otherwise {
              sq.ptr.onFree.valid := True
            }
          }
        }

        when(feed.doit && feed.skip){
          setup.cacheStore.cmd.valid := False
          feed.fire := True
          sq.ptr.onFree.valid := True
          ptr.free := ptr.free + 1
          ptr.writeBack := ptr.writeBack + 1
        }

        when(sq.ptr.onFree.valid) {
          ptr.free := ptr.free + 1
          ptr.priority := ptr.priority |<< 1
          when(ptr.priority === 0){
            ptr.priority := (default -> true)
          }
          for(reg <- regs) when(ptr.freeReal === reg.id){
            reg.valid := False
            reg.commited := False
          }
        }
      }
    }

    val flush = new Area{
      val busy = RegInit(False)
      val doit = RegInit(False)
      val cmdPtr, rspPtr = Reg(UInt(cache.lineRange.size+1 bits))
      def cmd = setup.cacheStore.cmd
      def rsp = setup.cacheStore.rsp

      doit := sq.ptr.commit === sq.ptr.free
      when(setup.flushPort.request){
        cmdPtr := 0
        rspPtr := 0
        busy   := True
        doit   := False
      }

      when(busy){
        fetch.getStage(0).haltIt()
      }

      cmd.flush := False
      when(busy && doit){
        cmd.valid := !cmdPtr.msb
        cmd.flush := True
        cmd.address(cache.lineRange) := cmdPtr.resized

        when(cmd.fire){
          cmdPtr := cmdPtr + 1
        }
        when(rsp.fire && !rsp.generationKo){
          when(rsp.redo){
            cmdPtr := rspPtr
            store.writeback.generation := !store.writeback.generation
          } otherwise {
            rspPtr := rspPtr + 1
          }
        }
        when(rspPtr.msb && !cache.writebackBusy){
          busy := False
          setup.flushPort.served := True
        }
      }
    }

    val special = new Area{
      val lqOnTop = lq.mem.robId.readAsync(lq.ptr.freeReal) === commit.currentCommitRobId
      val sqOnTop = sq.mem.robId.readAsync(sq.ptr.commitReal) === commit.currentCommitRobId
      val storeWriteBackUsable = sq.ptr.writeBack === sq.ptr.commit

      val hitStoreIo  = sq.mem.io.readAsync(sq.ptr.commitReal)
      val hitStoreAmo = sq.mem.amo.readAsync(sq.ptr.commitReal)
      val hitStoreSc  = sq.mem.sc.readAsync(sq.ptr.commitReal)
      val storeHit = sqOnTop && storeWriteBackUsable && sq.regs.map(reg => reg.valid && reg.waitOn.writeback).read(sq.ptr.commitReal) && (hitStoreIo || hitStoreAmo || hitStoreSc)
      val loadHit = lqOnTop && lq.regs.map(reg => reg.valid && reg.waitOn.commit).read(lq.ptr.freeReal) && lq.mem.io.readAsync(lq.ptr.freeReal)
      val hit = storeHit || loadHit

      val fire = CombInit(RegNext(peripheralBus.rsp.fire) init(False))
      val enabled = RegInit(False) setWhen(hit) clearWhen(fire)
      val isStore = RegNextWhen(storeHit, hit)
      val isLoad = RegNextWhen(!storeHit, hit)
      val cmdSent = RegInit(False) setWhen(peripheralBus.cmd.fire) clearWhen(fire)

      val robId = RegNext(commit.currentCommitRobId)
      val loadPhysRd = RegNext(lq.mem.physRd.readAsync(lq.ptr.freeReal))
      val loadAddress = RegNext(lq.mem.addressPost.readAsync(lq.ptr.freeReal))
      val loadSize = RegNext(lq.regs.map(_.address.size).read(lq.ptr.freeReal))
      val loadWriteRd = RegNext(lq.mem.writeRd.readAsync(lq.ptr.freeReal))
      val storeAddress = RegNextWhen(setup.cacheStore.cmd.address, hit)
      val storeSize = RegNextWhen(store.writeback.feed.size, hit)
      val storeData = RegNextWhen(setup.cacheStore.cmd.data, hit)
      val storeMask = RegNextWhen(setup.cacheStore.cmd.mask, hit)
      val storeAmo = RegNextWhen(hitStoreAmo, hit)
      val storeSc = RegNextWhen(hitStoreSc, hit)
      val address = isStore ? storeAddress otherwise loadAddress

      val isIo = !(isStore && (storeAmo || storeSc))
      val isAtomic = !isIo

      val wakeRob = Flow(WakeRob())
      wakeRob.valid := False
      wakeRob.robId := robId

      val wakeRf = Flow(WakeRegFile(decoder.PHYS_RD, needBypass = false))
      wakeRf.valid := False
      wakeRf.physical := loadPhysRd

      peripheralBus.cmd.valid   := enabled && !cmdSent && isIo
      peripheralBus.cmd.write   := isStore
      peripheralBus.cmd.address := address
      peripheralBus.cmd.size    := isStore ? storeSize otherwise loadSize
      peripheralBus.cmd.data    := storeData
      peripheralBus.cmd.mask    := storeMask

      setup.specialTrap.valid      := False
      setup.specialTrap.robId      := robId
      setup.specialTrap.cause      := (isStore ? U(CSR.MCAUSE_ENUM.STORE_ACCESS_FAULT) otherwise U(CSR.MCAUSE_ENUM.LOAD_ACCESS_FAULT)).resized
      setup.specialTrap.tval       := B(address)
      setup.specialTrap.skipCommit := True
      setup.specialTrap.reason     := ScheduleReason.TRAP

      setup.specialCompletion.valid := False
      setup.specialCompletion.id    := robId

      when(peripheralBus.rsp.fire) {
        load.pipeline.cacheRsp.peripheralOverride := True
        setup.specialCompletion.valid := True

        setup.specialTrap.valid := peripheralBus.rsp.error

        setup.rfWrite.valid               := isLoad && loadWriteRd
        setup.rfWrite.address             := loadPhysRd
        setup.rfWrite.robId               := robId
        load.pipeline.cacheRsp.rspAddress := loadAddress
        load.pipeline.cacheRsp.rspSize    := loadSize
        load.pipeline.cacheRsp.rspRaw     := peripheralBus.rsp.data

        when(isLoad && loadWriteRd) {
          wakeRob.valid := True
          wakeRf.valid  := True
        }
      }

      val atomic = new StateMachine{
        val IDLE, LOAD_CMD, LOAD_RSP, ALU, COMPLETION, SYNC, TRAP = new State
        setEntry(IDLE)

        val readed = Reg(Bits(XLEN bits))
        val alu = new AtomicAlu(
          op   = sq.mem.op,
          swap = sq.mem.swap,
          mem  = readed,
          rf   = storeData
        )

        val result = Reg(Bits(XLEN bits))
        val reservationHit = RegNext(lq.reservation.valid && lq.reservation.address === storeAddress)

        when(enabled && isAtomic){
          setup.cacheLoad.translated.physical := storeAddress
          setup.cacheLoad.translated.abord    := False
        }

        val lockPort = setup.cache.lockPort
        lockPort.valid := False
        lockPort.address := storeAddress.resized
        setup.cacheLoad.cmd.unlocked := True //As we already fenced on the dispatch stage
        when(!isActive(IDLE)) {
          lockPort.valid := True
        }

        IDLE whenIsActive {
          when(enabled && isAtomic){
            when(sq.ptr.commit === sq.ptr.free){
              when(storeSc){
                goto(ALU)
              } otherwise {
                goto(LOAD_CMD)
              }
            }
          }
        }

        LOAD_CMD whenIsActive{
          val cmd = setup.cacheLoad.cmd //If you move that in another stage, be carefull to update loadFeedAt usages (sq d$ writeback rsp delay)
          cmd.valid   := True
          cmd.virtual := storeAddress
          cmd.size    := storeSize
          cmd.redoOnDataHazard := False
          when(cmd.fire){
            goto(LOAD_RSP)
          }
        }

        //TODO lock the cache line !
        LOAD_RSP whenIsActive{
          val rsp = setup.cacheLoad.rsp
          readed := load.pipeline.cacheRsp.rspFormated
          when(rsp.fire){
            setup.rfWrite.valid        setWhen(sq.mem.writeRd)
            setup.rfWrite.address      := sq.mem.physRd
            setup.rfWrite.robId        := robId

            load.pipeline.cacheRsp.rspAddress := storeAddress
            load.pipeline.cacheRsp.rspSize    := storeSize

            when(rsp.redo){
              goto(IDLE)
            } elsewhen (rsp.fault) {
              goto(TRAP)
            } otherwise {
              goto(ALU)
            }
          }
        }

        TRAP whenIsActive{
          setup.specialTrap.valid := True
          goto(IDLE)
        }

        ALU whenIsActive{
          result := alu.result
          goto(COMPLETION)
        }

        COMPLETION whenIsActive{
          setup.specialCompletion.valid := True
          wakeRob.valid setWhen(sq.mem.writeRd)

          load.pipeline.hitSpeculation.wakeRf.valid setWhen(sq.mem.writeRd)
          load.pipeline.hitSpeculation.wakeRf.physical := sq.mem.physRd

          when(storeSc){
            setup.rfWrite.valid      setWhen(sq.mem.writeRd)
            setup.rfWrite.address := sq.mem.physRd
            setup.rfWrite.robId   := robId
            setup.rfWrite.data    := 0
            setup.rfWrite.data(0) := !reservationHit
          }

          goto(SYNC)
        }

        SYNC.whenIsActive{
          when(storeAmo) {
            store.writeback.feed.data := result
          }
          when(storeSc && !reservationHit){
            store.writeback.feed.skip := True
          }

          when(sq.ptr.onFree.valid) {
            fire := True
            when(storeSc) {
              lq.reservation.valid := False
            }
            goto(IDLE)
          }
        }

        frontend.pipeline.dispatch.haltIt(isActive(SYNC))
      }
    }


    //Store some robId related context for later uses
    def remapped[T <: Data](key : Stageable[T]) : Seq[T] = (0 until Frontend.DISPATCH_COUNT).map(allocStage(key, _))
    def writeLine[T <: Data](key : Stageable[T]) : Unit = writeLine(key, remapped(key))
    def writeLine[T <: Data](key : Stageable[T], value : Seq[T]) : Unit  = {
      rob.write(
        key = key,
        size = DISPATCH_COUNT,
        value = value,
        robId = allocStage(ROB.ID),
        enable = allocStage.isFireing
      )
    }
    writeLine(LSU_ID)
    writeLine(SQ_ALLOC)
    writeLine(LQ_ALLOC)

    when(rescheduling.valid){
      lq.regs.foreach(_.valid := False)
      lq.ptr.free := 0
      lq.ptr.alloc := 0
      lq.ptr.priority := 0
      for(reg <- sq.regs){
        reg.valid clearWhen(!reg.commitedNext)
        reg.allLqIsYounger := True
      }
      sq.ptr.alloc := sq.ptr.commitNext
      special.enabled := False
    }


    val whitebox = new AreaRoot{
      val stage = frontend.pipeline.dispatch
      Verilator.public(stage(ROB.ID))

      val sqAlloc = for(slotId <- 0 until DECODE_COUNT) yield new Area{
        val valid = Verilator.public(stage.isFireing && stage(DISPATCH_MASK, slotId) && stage(SQ_ALLOC, slotId))
        val id = Verilator.public(CombInit(stage(SQ_ID, slotId)))
      }

      val sqFree = Verilator.public(sq.ptr.onFree.combStage())
    }

    rob.release()
    decoder.release()
    frontend.release()
    fetch.release()
    translationService.release()
  }


}
