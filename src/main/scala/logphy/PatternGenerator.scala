package edu.berkeley.cs.ucie.digital
package logphy

import chisel3._
import chisel3.util._
import interfaces._

class PatternGeneratorIO(
    afeParams: AfeParams,
) extends Bundle {
  val transmitInfo = Flipped(Decoupled(new Bundle {
    val pattern = TransmitPattern()
    val timeoutCycles = UInt(32.W)
    val sideband = Bool()
    // val mainbandSel = MainbandSel()
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
    statusValid := false.B
    errorPerLane := 0.U
    errorAllLane := 0.U
  }.otherwise {
    // writeInProgress := false.B
    // readInProgress := false.B
  }

  //TODO: instantiate a LFSR scrambler as per p73. enable/disable scrmabling accroding to pattern

  val clockPatternShiftReg = RegInit("h_aaaa_aaaa_aaaa_aaaa_0000_0000".U) //64 strobe + 32 low
  
  val clockRepairShiftReg = RegInit("h_aaaa_00".U) //16 strobe + 8 low on track, clk_p, clk_n
  
  val valTrainShiftReg = RegInit("h_f0".U) //1111_0000 on valid
  
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

  val patternToTransmit = Wire(0.U(afeParams.sbSerializerRatio.W))
  val patternToTransmitMb = Wire(Vec(afeParams.mbLanes, Bits(afeParams.mbSerializerRatio.W)))
  val patternDetectedCount = RegInit(0.U)
  val patternWrittenCount = RegInit(0.U)

  // Look up tables for max sent/detected pattern count (# of iterations)
  val patternWrittenCountMax = Seq(
    TransmitPattern.CLOCK_64_LOW_32 -> 4.U,
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

  /*Width douplers: for detecting incoming sequence */
  // look up table for desired chunked output width (for 1 iteration)
  val outWidth = pattern match {
    case TransmitPattern.CLOCK_64_LOW_32 => 96
    case TransmitPattern.CLK_REPAIR => 24
    case TransmitPattern.VAL_TRAIN => 8
    case TransmitPattern.MB_REVERSAL => 16
    case _ => 16 //Default
  }

  private val sidebandInWidthCoupler = new DataWidthCoupler(
    /** collect size of largest pattern */
    DataWidthCouplerParams(
      inWidth = afeParams.sbSerializerRatio,
      outWidth = outWidth,
    ),
  )
  //TOOD: important: need a vector of 16 of these data width couplers
  private val mainbandInWidthCoupler = new DataWidthCoupler(
    /** collect size of largest pattern */
    DataWidthCouplerParams(
      inWidth = afeParams.mbSerializerRatio,
      outWidth = outWidth,
    ),
  )
  when(sideband) { // for sideband
    io.mbAfe.txData.valid := false.B // never send thru MB

    sidebandInWidthCoupler.io.in <> io.sbAfe.rxData // both are flipped decoupled interface
    sidebandInWidthCoupler.io.out.ready := readInProgress
    io.sbAfe.txData.valid := writeInProgress
    io.sbAfe.txData.bits := patternToTransmit
  }.otherwise { // for mainband
    io.sbAfe.txData.valid := false.B // never send thru SB

    // TODO: in mbafe, need to incorporate a ready/valid interface for track, clk_p, clk_n, valid
    mainbandInWidthCoupler.io.in <> io.mbAfe.rxData
    sidebandInWidthCoupler.io.out.ready := readInProgress

    switch(pattern) {
      is(TransmitPattern.CLK_REPAIR) {
        // send through clk and track
      }
      is(TransmitPattern.VAL_TRAIN) {
        // send through valid
      }
      is(TransmitPattern.DATA) {
        io.mbAfe.txData.valid := writeInProgress
        io.mbAfe.txData.bits := patternToTransmitMb
        //NOTE: For patternToTransmitMb, in reality should use a widthcoupler to convert 16bit mb msg (per lane) to mbSerializerRatio width.
        //Simplified here because mbSerializerRatio is by default 16.
      }
    }
    
  }



  // NOTE: point 5 on p86, repetitively sending patterns is simplified to sending {MAX} patterns
  when(inProgress) {
    //TODO: same or different timeout for mb data patterns?
    timeoutCycles := timeoutCycles - 1.U
    when(timeoutCycles === 0.U) {
      status := MessageRequestStatusType.ERR
      writeInProgress := false.B
      readInProgress := false.B
    }.elsewhen( //Todo: should we just stop sending if detectedcount=max?
      (patternWrittenCount === MuxLookup(pattern, 0.U)(
        patternWrittenCountMax,
      )) && (patternDetectedCount === MuxLookup(pattern, 0.U)(
        patternDetectedCountMax,
      )),
    ) {
      statusValid := true.B
      status := MessageRequestStatusType.SUCCESS
      writeInProgress := false.B
      readInProgress := false.B
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
          afeParams.sbSerializerRatio - 1,
          0,
        )
        when(io.sbAfe.txData.fire) {
          clockPatternShiftReg := (clockPatternShiftReg >> afeParams.sbSerializerRatio.U).asUInt &
            (clockPatternShiftReg <<
              (clockPatternShiftReg.getWidth.U - afeParams.sbSerializerRatio.U))

          patternWrittenCount := patternWrittenCount + 1.U
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

            val patternToDetect = "h_aaaa_aaaa_aaaa_aaaa_0000_0000".U(outWidth.W)
            when(sidebandInWidthCoupler.io.out.fire) {

              /** detect clock UI pattern -- as long as the pattern is correctly
                * aligned, this is simple
                *
                * TODO: should I do more for pattern detection? right now for
                * pattern detecting 128 clock UI, I count the clock cycles in chunks
                * of 4 and add 4 if the pattern is 1010, but this wouldn't work if
                * it is misaligned for any reason
                */
              when(sidebandInWidthCoupler.io.out.bits === patternToDetect) {
                patternDetectedCount := patternDetectedCount + 1.U
              }
            }

      }

      is(TransmitPattern.MB_REVERSAL) {
        when(mainbandInWidthCoupler.io.out.fire) {
          //just comparing per-lane id
          patternDetectedCount := patternDetectedCount + 1.U
          for (i <- 0 until 16) {
            // compare per lane id lane by lane and fill results into errorPerLane.
            // count up errorAllLane if mismatch
            when (mbReverseConstVec(i) === mainbandInWidthCoupler.io.out.bits(i).asUInt) {
              errorPerLane(i) := 0.U
            }.otherwise {
              errorPerLane(i) := 1.U
              errorAllLane := errorAllLane + 1.U
            }
          }
        }
      }
    }

  }

}
