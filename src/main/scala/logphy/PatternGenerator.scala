package edu.berkeley.cs.ucie.digital
package logphy

import chisel3._
import chisel3.util._
import interfaces._
import chisel3.stage.ChiselStage

//Pattern Generator issue:
//    1: The code does not account for consecutive pattern(i.e. patternxxxxxgarbagexxxxpattern also passes)
//    2: The code does not implement the following:(from sbinit)
//              If a UCIe Module Partner detects the pattern successfully on at least one of its
//              sideband data-clock Receiver combination, it must stop sending data and clock on
//              its sideband Transmitters after four more iterations
//   

class PatternGeneratorIO(
    afeParams: AfeParams,
) extends Bundle {
  val transmitInfo = Flipped(Decoupled(new Bundle {
    val pattern = TransmitPattern()
    val timeoutCycles = UInt(32.W)
    val sideband = Bool()
  })) // data to transmit & receive over SB
  val transmitPatternStatus = Decoupled(new PatternGenRequestStatus(afeParams))
}

class PatternGenerator(
    afeParams: AfeParams,
) extends Module {
  val io = IO(new Bundle {
    val patternGeneratorIO = new PatternGeneratorIO(afeParams)
    val mbAfe = new MainbandAfeIo(afeParams)
    val sbAfe = new SidebandAfeIo(afeParams)

  })

  // Status Regs Begin -----------------------------------------------
  private val writeInProgress = RegInit(false.B)
  private val readInProgress = RegInit(false.B)
  private val inProgress = writeInProgress || readInProgress
  private val pattern = RegInit(TransmitPattern.NO_PATTERN)
  private val sideband = RegInit(true.B)
  private val timeoutCycles = RegInit(0.U)
  private val status = RegInit(MessageRequestStatusType.SUCCESS)
  private val statusValid = RegInit(false.B)
  private val errorPerLane = RegInit(0.U(afeParams.mbLanes.W))
  private val errorAllLane = RegInit(0.U(4.W))
  // Status Regs End -------------------------------------------------

  io.patternGeneratorIO.transmitInfo.ready := (inProgress === false.B)
  io.patternGeneratorIO.transmitPatternStatus.valid := statusValid
  io.patternGeneratorIO.transmitPatternStatus.bits.status := status
  io.patternGeneratorIO.transmitPatternStatus.bits.errorPerLane := errorPerLane
  io.patternGeneratorIO.transmitPatternStatus.bits.errorAllLane := errorAllLane

  when(io.patternGeneratorIO.transmitInfo.fire) { //fire exists?
    writeInProgress := true.B
    readInProgress := true.B
    pattern := io.patternGeneratorIO.transmitInfo.bits.pattern
    sideband := io.patternGeneratorIO.transmitInfo.bits.sideband
    timeoutCycles := io.patternGeneratorIO.transmitInfo.bits.timeoutCycles
    errorPerLane := 0.U
    errorAllLane := 0.U
  }



  //TODO: instantiate a LFSR scrambler as per p73. enable/disable scrmabling accroding to pattern 

  val clockPatternShiftReg = RegInit("h_aaaa_aaaa_aaaa_aaaa_0000_0000_aaaa_aaaa_aaaa_aaaa_0000_0000".U) //64 strobe + 32 low
  val clockRepairShiftReg = RegInit("h_aaaa_aaaa_0000".U) //16 cycles of strobe(32bits on DDR) + 8 low on track, clk_p, clk_n
  val valTrainShiftReg = RegInit("h_f0".U) //1111_0000 on valid
  val clockPatternMatchShiftReg = RegInit("h_aaaa_aaaa_aaaa_aaaa_0000_0000_aaaa_aaaa_aaaa_aaaa_0000_0000".U) 
  val clockPatternPartialMatches = RegInit(false.B)
  val clockRepairMatchShiftReg = RegInit("h_aaaa_aaaa_0000_aaaa_aaaa_0000_aaaa_aaaa_0000".U)


  when(io.patternGeneratorIO.transmitPatternStatus.fire){
    statusValid := false.B
    clockPatternShiftReg := "h_aaaa_aaaa_aaaa_aaaa_0000_0000_aaaa_aaaa_aaaa_aaaa_0000_0000".U
    clockPatternMatchShiftReg := "h_aaaa_aaaa_aaaa_aaaa_0000_0000_aaaa_aaaa_aaaa_aaaa_0000_0000".U
    //Other necessary resets goes here
  }

  // Function to reverse the bits of ID
  def reversedBinary(id: Int): UInt = {
    val reversedBinaryString = id.toBinaryString.reverse.padTo(8, '0').reverse
    val reversedBinaryInt = Integer.parseInt(reversedBinaryString, 2)
    reversedBinaryInt.U(8.W)
  }

  // TODO: not too sure about the vec[UInt] and seq[UInt] here
  // Precalculating the constant values for the vector. see p94 per lane ID pattern: 0101_8bitID_0101
  val calculateMbReverseVec: Seq[UInt] = (0 until afeParams.mbLanes).map { pos =>
    val prefixSuffix = "b0101".U(4.W)               // First and last 4 bits are 0101
    val middle = reversedBinary(pos)                // Middle 8 bits as per the custom logic
    Cat(prefixSuffix, middle, prefixSuffix)         // Concatenate: prefix + middle + suffix
  }

  // Creating the constant Vec
  val mbReverseConstVec: Vec[UInt] = VecInit(calculateMbReverseVec)

  val patternToTransmit = Wire(UInt(afeParams.sbSerializerRatio.W))
  val patternToTransmitMb = Wire(Vec(afeParams.mbLanes, Bits(afeParams.mbSerializerRatio.W)))
  val patternDetectedCount = RegInit(0.U(10.W))
  val patternWrittenCount = RegInit(0.U(10.W))

  // Look up tables for max sent/detected pattern count (# of iterations)
  val patternWrittenCountMax = Seq(
    TransmitPattern.CLOCK_64_LOW_32 -> 6.U, //Naive implementation: Just send more to give enough time for RX and TX
    TransmitPattern.CLK_REPAIR -> 128.U,
    TransmitPattern.VAL_TRAIN -> 128.U,
    TransmitPattern.MB_REVERSAL -> 128.U,
  )

  val patternDetectedCountMax = Seq(
    TransmitPattern.CLOCK_64_LOW_32 -> 2.U,
    TransmitPattern.CLK_REPAIR -> 16.U,
    TransmitPattern.VAL_TRAIN -> 16.U, //p92.3: successful with 16 detection
    TransmitPattern.MB_REVERSAL -> 1.U
  )


  // /*Width douplers: for detecting incoming sequence */
  // // look up table for desired chunked output width (for 1 iteration)
  // val outWidth = pattern match {
  //   case TransmitPattern.CLOCK_64_LOW_32 => 96
  //   case TransmitPattern.CLK_REPAIR => 24
  //   case TransmitPattern.VAL_TRAIN => 8
  //   case TransmitPattern.MB_REVERSAL => 16
  //   case _ => 16 //Default
  // }

  patternToTransmit := 0.U
  patternToTransmitMb := VecInit(Seq.fill(afeParams.mbLanes)(0.U(afeParams.mbSerializerRatio.W)))
  // SBAFE
  io.sbAfe.txData.valid := Mux(sideband,writeInProgress,false.B)
  io.sbAfe.txData.bits := patternToTransmit
  io.sbAfe.pllLock := 0.U       //NOT IMPLEMENTED
  io.sbAfe.rxEn := Mux(sideband,readInProgress,false.B)
  io.sbAfe.rxData.ready := Mux(sideband,readInProgress,false.B)

  // MBAFE
  io.mbAfe.txData.valid := Mux(sideband,false.B,writeInProgress)
  io.mbAfe.txData.bits := patternToTransmitMb
  io.mbAfe.rxData.ready :=  Mux(sideband,false.B,readInProgress)


  // NOTE: point 5 on p86, repetitively sending patterns is simplified to sending {MAX} patterns 
          //No, this logic still sends until timeout if detected is not met
  when(inProgress) {
    //TODO: same or different timeout for mb data patterns?
    timeoutCycles := timeoutCycles - 1.U
    when(timeoutCycles === 0.U) {
      statusValid := true.B
      status := MessageRequestStatusType.ERR
      writeInProgress := false.B
      readInProgress := false.B
      patternWrittenCount := 0.U
      patternDetectedCount := 0.U
    }.elsewhen( //Q: should we just stop sending if detectedcount=max? 
                //A: NO. spec says(e.g., valtrain): the UCIe Module sends 128 iterations of VALTRAIN pattern ...
                //   So it must send 128 iteration no matter how much is detected.
      (patternWrittenCount >= MuxLookup(pattern, 0.U)(
        patternWrittenCountMax,
      )) && (patternDetectedCount >= MuxLookup(pattern, 0.U)(
        patternDetectedCountMax,
      )),
    ) {
      statusValid := true.B
      status := MessageRequestStatusType.SUCCESS
      writeInProgress := false.B
      readInProgress := false.B
      patternWrittenCount := 0.U
      patternDetectedCount := 0.U
    }
  }

  when(writeInProgress) {
    switch(pattern) {

      /** Patterns may be different lengths, etc. so may be best to handle
        * separately, for now
        */
      // is(TransmitPattern.VAL_TRAIN) {
      //   patternToTransmit := clockPatternShiftReg(
      //     afeParams.sbSerializerRatio - 1,
      //     0,
      //   )
      //   when(io.sbAfe.txData.fire) {
      //     clockPatternShiftReg := (clockPatternShiftReg >> afeParams.sbSerializerRatio.U).asUInt &
      //       (clockPatternShiftReg <<
      //         (clockPatternShiftReg.getWidth.U - afeParams.sbSerializerRatio.U))

      //     patternWrittenCount := patternWrittenCount + 1.U
      //   }
      // }
      is(TransmitPattern.CLOCK_64_LOW_32) {
        patternToTransmit := clockPatternShiftReg(
          clockPatternShiftReg.getWidth -1,
          clockPatternShiftReg.getWidth -afeParams.sbSerializerRatio)
        when(io.sbAfe.txData.fire) {
          when(clockPatternMatchShiftReg === "h_aaaa_aaaa_aaaa_aaaa_0000_0000_aaaa_aaaa_aaaa_aaaa_0000_0000".U){
            patternWrittenCount := patternWrittenCount + 2.U //If sees this, meaning it wraps again, need to increment twice
          }.otherwise{
            patternWrittenCount := patternWrittenCount + 1.U
          }
          clockPatternShiftReg := (clockPatternShiftReg << afeParams.sbSerializerRatio.U) |
            (clockPatternShiftReg >>
              (clockPatternShiftReg.getWidth.U - afeParams.sbSerializerRatio.U))

        }
      }

      is(TransmitPattern.MB_REVERSAL) {
        patternToTransmitMb := mbReverseConstVec

        when(io.mbAfe.txData.fire) {
          patternWrittenCount := patternWrittenCount + 1.U
        }
      }
    }

  }

  when(readInProgress) {
    switch(pattern) {

      is(TransmitPattern.CLOCK_64_LOW_32) {
            when(io.sbAfe.rxData.fire) {
              when(io.sbAfe.rxData.bits === clockPatternMatchShiftReg(191,192-afeParams.sbSerializerRatio)) {
                patternDetectedCount := patternDetectedCount + 1.U + clockPatternPartialMatches
                when(clockPatternMatchShiftReg === "h_aaaa_aaaa_aaaa_aaaa_0000_0000_aaaa_aaaa_aaaa_aaaa_0000_0000".U){
                  clockPatternPartialMatches := false.B
                }.otherwise{
                  clockPatternPartialMatches := true.B
                }
              }.otherwise{
                // Patterns needs to be consecutive
                patternDetectedCount := 0.U
                clockPatternPartialMatches := false.B
              }
              //Shift the pattern Reg
              clockPatternMatchShiftReg := (clockPatternMatchShiftReg << afeParams.sbSerializerRatio.U) |
              (clockPatternMatchShiftReg >>(clockPatternMatchShiftReg.getWidth.U - afeParams.sbSerializerRatio.U))
              }
      }


      // is(TransmitPattern.MB_REVERSAL) {
      //   when(mainbandInWidthCoupler.io.out.fire) {
      //     //just comparing per-lane id
      //     patternDetectedCount := patternDetectedCount + 1.U
      //     for (i <- 0 until 16) {
      //       // compare per lane id lane by lane and fill results into errorPerLane.
      //       // count up errorAllLane if mismatch
      //       when (mbReverseConstVec(i) === mainbandInWidthCoupler.io.out.bits(i).asUInt) {
      //         errorPerLane(i) := 0.U
      //       }.otherwise {
      //         errorPerLane(i) := 1.U
      //         errorAllLane := errorAllLane + 1.U
      //       }
      //     }
      //   }
      // }

    }

  }

}
