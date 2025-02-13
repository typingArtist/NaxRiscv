package naxriscv.frontend

import spinal.core._
import spinal.lib._
import spinal.lib.pipeline.Connection.{DIRECT, M2S}
import naxriscv.utilities._
import naxriscv._
import naxriscv.Global._
import naxriscv.Frontend._



class DecompressorPlugin() extends Plugin{
  val setup = create early new Area{
    val frontend = getService[FrontendPlugin]
    frontend.retain()
    frontend.pipeline.connect(frontend.pipeline.aligned, frontend.pipeline.decompressed)(DIRECT()) //TODO optional
  }

  val logic = create late new Area{
    val stage = setup.frontend.pipeline.decompressed
    import stage._
    for(i <- 0 until DECODE_COUNT) {
      stage(INSTRUCTION_DECOMPRESSED, i) := stage(INSTRUCTION_ALIGNED, i)
      //TODO don't forget to mask INSTRUCTION_DECOMPRESSED upper bits if RVC
    }
    setup.frontend.release()
  }
}