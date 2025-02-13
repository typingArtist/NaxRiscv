package spinal.lib.pipeline

import naxriscv.utilities.Misc
import spinal.core._
import spinal.lib._

import scala.collection.mutable
import scala.collection.mutable.ArrayBuffer





case class PipelineTop() extends Component {
  val io = new Bundle {
    val source = slave Stream(UInt(8 bits))
    val sink   = master Flow(UInt(8 bits))

    val cond0 = in UInt(8 bits)
  }

  val pipeline = new Pipeline{
    val s0, s1, s2 = new Stage()
    val A, B, C = Stageable(UInt(8 bits))

    import Connection._
    connect(s0, s1)(M2S())
    connect(s1, s2)(M2S())

    val onS0 = new Area {
      import s0._
      valid := io.source.valid
      io.source.ready := s0.isReady
      A := io.source.payload
    }

    val onS1 = new Area{
      import s1._
      when(io.cond0 === 0){
        haltIt()
      }
//      when(io.cond0 === 1){
//        flushIt()
//      }
      when(io.cond0 === 2){
        flushNext()
      }

      B := A + 1
      s1(C, "miaou") :=  A + 2
      (C,"wuff") :=  A + 3
      (C, "wuff2") :=  A + 4

      {
        implicit val offset = new StageableOffset("yololo")
        C := U(5)
      }
    }

    val onS2 = new Area {
      import s2._
      io.sink.valid := internals.output.valid
      io.sink.payload := B + s2(C, "miaou") + s2(C, "wuff") +(C, "wuff2") +(C, "yololo")
    }

  }
  pipeline.build()
}

object PipelinePlay extends App{
  SpinalVerilog{
    PipelineTop()
  }
}