package naxriscv.units

import naxriscv.Frontend.MICRO_OP
import naxriscv.{Frontend, Global}
import naxriscv.interfaces._
import naxriscv.riscv._
import naxriscv.utilities.Plugin
import spinal.core._
import spinal.lib.Flow
import spinal.lib.pipeline.Stageable

object BranchPlugin extends AreaObject {
  val SEL = Stageable(Bool())
  val NEED_BRANCH = Stageable(Bool())
  val COND = Stageable(Bool())
  val PC_TRUE = Stageable(Global.PC)
  val PC_FALSE = Stageable(Global.PC)
  val PC_BRANCH = Stageable(Global.PC)
  val UNSIGNED = Stageable(Bool())
  val EQ = Stageable(Bool())
  val LESS = Stageable(Bool())
  object BranchCtrlEnum extends SpinalEnum(binarySequential){
    val INC,B,JAL,JALR = newElement()
  }
  object BRANCH_CTRL extends Stageable(BranchCtrlEnum())
}

class BranchPlugin(euId : String) extends Plugin {
  withPrefix(euId)

  import BranchPlugin._
  val aluStage = 0
  val branchStage = 1



  val setup = create early new Area{
    val eu = getService[ExecutionUnitBase](euId)
    eu.retain()

    def add(microOp: MicroOp, decoding : eu.DecodeListType) = {
      eu.addMicroOp(microOp)
      eu.setStaticCompletion(microOp, branchStage)
      eu.addDecoding(microOp, decoding)
    }

    val baseline = eu.DecodeList(SEL -> True)

    eu.setDecodingDefault(SEL, False)
//    add(Rvi.JAL , baseline ++ List(BRANCH_CTRL -> BranchCtrlEnum.JAL, ALU_CTRL -> AluCtrlEnum.ADD_SUB))
//    add(Rvi.JALR, baseline ++ List(BRANCH_CTRL -> BranchCtrlEnum.JALR, ALU_CTRL -> AluCtrlEnum.ADD_SUB, RS1_USE -> True))
    add(Rvi.BEQ , baseline ++ List(BRANCH_CTRL -> BranchCtrlEnum.B))
    add(Rvi.BNE , baseline ++ List(BRANCH_CTRL -> BranchCtrlEnum.B))
    add(Rvi.BLT , baseline ++ List(BRANCH_CTRL -> BranchCtrlEnum.B, UNSIGNED -> False))
    add(Rvi.BGE , baseline ++ List(BRANCH_CTRL -> BranchCtrlEnum.B, UNSIGNED -> False))
    add(Rvi.BLTU, baseline ++ List(BRANCH_CTRL -> BranchCtrlEnum.B, UNSIGNED -> True))
    add(Rvi.BGEU, baseline ++ List(BRANCH_CTRL -> BranchCtrlEnum.B, UNSIGNED -> True))

    val reschedule = getService[CommitService].newSchedulePort(canJump = true, canTrap = true)
  }

  val logic = create late new Area{
    val sliceShift = if(Frontend.RVC) 1 else 2

    val eu = getService[ExecutionUnitBase](euId)
    val process = new Area {
      val stage = eu.getExecute(0)

      import stage._

      val src1 = S(eu(IntRegFile, RS1))
      val src2 = S(eu(IntRegFile, RS2))

      val sub = src1 - src2
      LESS := (src1.msb === src2.msb) ? sub.msb | Mux(UNSIGNED, src2.msb, src1.msb)
      EQ := src1 === src2

      COND := BRANCH_CTRL.mux(
        BranchCtrlEnum.INC  -> False,
        BranchCtrlEnum.JAL  -> True,
        BranchCtrlEnum.JALR -> True,
        BranchCtrlEnum.B    -> MICRO_OP(14 downto 12).mux(
          B"000"  -> EQ,
          B"001"  -> !EQ,
          M"1-1"  -> !LESS,
          default -> LESS
        )
      )

      PC_TRUE := U(S(Global.PC) + IMM(Frontend.MICRO_OP).b_sext)
      val slices = Frontend.INSTRUCTION_SLICE_COUNT+1
      PC_FALSE := Global.PC + (slices << sliceShift)
      PC_BRANCH := NEED_BRANCH ? stage(PC_TRUE) | stage(PC_FALSE)
      NEED_BRANCH := COND //For now, until prediction are iplemented
    }

    val branch = new Area{
      val stage = eu.getExecute(branchStage)
      import stage._
      setup.reschedule.valid := isFireing && SEL && NEED_BRANCH
      setup.reschedule.robId := ExecutionUnitKeys.ROB_ID
      setup.reschedule.cause := 0
      setup.reschedule.tval := 0
      setup.reschedule.pcTarget := PC_BRANCH

      setup.reschedule.trap := PC_BRANCH(0, sliceShift bits) =/= 0
      setup.reschedule.skipCommit := True
    }
    eu.release()
  }
}
