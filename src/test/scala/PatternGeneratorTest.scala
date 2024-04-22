package edu.berkeley.cs.ucie.digital
package logphy

import chisel3._
import chisel3.experimental.BundleLiterals._
import chiseltest._
import interfaces._
import org.scalatest.flatspec.AnyFlatSpec

class PatternGeneratorTest extends AnyFlatSpec with ChiselScalatestTester {
  val afeParams = AfeParams()
  behavior of "main/sideband pattern generator"
  it should "detect clock pattern no delay" in {
    test(new PatternGenerator(afeParams = afeParams)).withAnnotations(Seq(VcsBackendAnnotation,WriteVcdAnnotation)) {
      c =>
        initPorts(c)
        testClockPatternSideband(c)
    }
  }

  it should "detect clock pattern no delay twice" in {
    test(new PatternGenerator(afeParams = afeParams)).withAnnotations(Seq(VcsBackendAnnotation,WriteVcdAnnotation)) {
      c =>
        initPorts(c)
        testClockPatternSideband(c)
        c.clock.step()
        c.clock.step()
        c.clock.step()
        testClockPatternSideband(c)
    }
  }

  private def initPorts(c: PatternGenerator) = {
    c.io.patternGeneratorIO.transmitInfo
      .initSource()
      .setSourceClock(c.clock)
    c.io.patternGeneratorIO.transmitPatternStatus
      .initSink()
      .setSinkClock(c.clock)
    c.io.sbAfe.rxData
      .initSource()
      .setSourceClock(c.clock)
    c.io.sbAfe.txData
      .initSink()
      .setSinkClock(c.clock)
    c.clock.setTimeout(10000)
  }

  private def testClockPatternSideband(c: PatternGenerator): Unit = {
    c.io.patternGeneratorIO.transmitInfo.ready.expect(true)
    c.io.sbAfe.rxData.ready.expect(false)
    c.io.sbAfe.txData.expectInvalid()
    c.io.patternGeneratorIO.transmitPatternStatus.expectInvalid()
    c.clock.step()

    c.io.patternGeneratorIO.transmitInfo.enqueueNow(
      chiselTypeOf(c.io.patternGeneratorIO.transmitInfo.bits).Lit(
        _.pattern -> TransmitPattern.CLOCK_64_LOW_32,
        _.timeoutCycles -> 80.U,
        _.sideband -> true.B,
      ),
    )

    // 2 input should make the detection pass
    val testInVector =Seq("h_aaaa_aaaa_aaaa_aaaa_0000_0000_aaaa_aaaa".U,
                          "h_aaaa_aaaa_0000_0000_aaaa_aaaa_aaaa_aaaa".U)
    // Expecting 6 output
    val testOutVector =Seq("h_aaaa_aaaa_aaaa_aaaa_0000_0000_aaaa_aaaa".U, //1
                           "h_aaaa_aaaa_0000_0000_aaaa_aaaa_aaaa_aaaa".U, //2
                           "h_0000_0000_aaaa_aaaa_aaaa_aaaa_0000_0000".U, //4
                           "h_aaaa_aaaa_aaaa_aaaa_0000_0000_aaaa_aaaa".U, //5
                           "h_aaaa_aaaa_0000_0000_aaaa_aaaa_aaaa_aaaa".U) //6


    print("Expecting Correct TX out...\n")
    fork {
      c.io.sbAfe.rxData.enqueueSeq(testInVector)
    }.fork {
      c.io.sbAfe.txData.expectDequeueSeq(testOutVector)
      print("[Correct] TX out \n")
    }.join()


    print("Expecting Correct status out...\n")
    c.io.patternGeneratorIO.transmitPatternStatus
      .expectDequeue(
          new PatternGenRequestStatus(afeParams).Lit(_.status -> MessageRequestStatusType.SUCCESS,
                                                    _.errorPerLane -> 0.U,
                                                    _.errorAllLane -> 0.U)
      )
    print("[Correct] Status Success \n")
  }
}