package edu.berkeley.cs.ucie.digital

import chisel3._
import chiseltest._
import org.scalatest.funspec.AnyFunSpec
import interfaces._
import sideband._
import logphy._


class dut2 (afeParams: AfeParams) extends Module{
    val io = IO(new Bundle{
        // sender 
        val enable = Input(Bool())
        val ready = Input(Bool())
        val msgHeader = Input(new SidebandMessageHeader())
        val data0 = Input(Bits(32.W))
        val data1 = Input(Bits(32.W))
        val msgOut = Output(Bits(32.W))
        // val phase = Output(UInt(2.W))
        val phase0Val = Output(Bool())
        val phase1Val = Output(Bool())
        val phase2Val = Output(Bool())
        val phase3Val = Output(Bool())
        val done = Output(Bool())
        
        // receiver 
        val sbAfe = new SidebandAfeIo(afeParams)
    })

    val sme = Module(new SidebandMessageEncoder())

    sme.io.enable := io.enable
    sme.io.ready := io.ready
    sme.io.msgHeaderIn := io.msgHeader
    sme.io.data0 := io.data0
    sme.io.data1 := io.data1
    io.msgOut := sme.io.msgOut
    // io.phase := sme.io.phase
    io.phase0Val := sme.io.phase0Val
    io.phase1Val := sme.io.phase1Val
    io.phase2Val := sme.io.phase2Val
    io.phase3Val := sme.io.phase3Val
    io.done := sme.io.done

    io.sbAfe.txData.bits := io.msgOut
    io.sbAfe.txData.valid := io.enable && !io.done
    io.sbAfe.pllLock := 1.U
    io.sbAfe.rxEn := 0.U
    io.sbAfe.rxData.ready := 0.U
    // io.sbAfe.txData.irdy := io.sbAfe.txData.valid   
}

class SidebandMessageDecoderTest extends AnyFunSpec with ChiselScalatestTester {
  describe("DUT Module with Decoupled3 Interface") {
     it("should properly transfer data from sender to receiver when ready and valid, with data") {
      test(
        new dut2(
          AfeParams()
        )
      ).withAnnotations(Seq(WriteVcdAnnotation)) { c =>
        // Initialize inputs
        c.io.enable.poke(true.B)
        c.io.ready.poke(true.B)  // Example data to send
        
        c.io.msgHeader.srcid.poke(SourceID.DieToDieAdapter)
        c.io.msgHeader.rsvd_00.poke(1.U)
        c.io.msgHeader.rsvd_01.poke(1.U)
        c.io.msgHeader.msgCode.poke(MsgCode.LinkMgmt_RDI_Req)
        c.io.msgHeader.rsvd_02.poke(1.U)
        c.io.msgHeader.opcode.poke(Opcode.MessageWith64bData)
        // Phase 1
        c.io.msgHeader.dp.poke(1.U)
        c.io.msgHeader.cp.poke(1.U)
        c.io.msgHeader.rsvd_10.poke(1.U)
        c.io.msgHeader.dstid.poke(1.U)
        c.io.msgHeader.msgInfo.poke(MsgInfo.ReturnCredit1)
        c.io.msgHeader.msgSubCode.poke(MsgSubCode.Active)
        // data
        c.io.data0.poke(5.U)
        c.io.data1.poke(9.U)

        while (!c.io.phase0Val.peek().litToBoolean) {
          c.clock.step()
        }
        println(s"msgOut at phase 0: ${c.io.msgOut.peek().litValue}")

        while (!c.io.phase1Val.peek().litToBoolean) {
          c.clock.step()
        }
        println(s"msgOut at phase 1: ${c.io.msgOut.peek().litValue}")

        while (!c.io.phase2Val.peek().litToBoolean) {
          c.clock.step()
        }
        println(s"msgOut at phase 2: ${c.io.msgOut.peek().litValue}")

        c.io.phase2Val.expect(true.B)
        c.io.msgOut.expect(5.U)

        while (!c.io.phase3Val.peek().litToBoolean) {
          c.clock.step()
        }
        println(s"msgOut at phase 3: ${c.io.msgOut.peek().litValue}")
        c.io.phase3Val.expect(true.B)
        c.io.msgOut.expect(9.U)
      }
    }


    it("should properly transfer data from sender to receiver when ready and valid, without data") {
      test(
        new dut2(
          AfeParams()
        )
      ).withAnnotations(Seq(WriteVcdAnnotation)) { c =>
        // Initialize inputs
        c.io.enable.poke(true.B)
        c.io.ready.poke(true.B)  // Example data to send
        
        // Chisel testbench does not allow .asUInt
        c.io.msgHeader.srcid.poke(SourceID.DieToDieAdapter)
        c.io.msgHeader.rsvd_00.poke(1.U)
        c.io.msgHeader.rsvd_01.poke(1.U)
        c.io.msgHeader.msgCode.poke(MsgCode.LinkMgmt_RDI_Req)
        c.io.msgHeader.rsvd_02.poke(1.U)
        c.io.msgHeader.opcode.poke(Opcode.MessageWithoutData)
        // Phase 1
        c.io.msgHeader.dp.poke(1.U)
        c.io.msgHeader.cp.poke(1.U)
        c.io.msgHeader.rsvd_10.poke(1.U)
        c.io.msgHeader.dstid.poke(1.U)
        c.io.msgHeader.msgInfo.poke(MsgInfo.RegularResponse)
        c.io.msgHeader.msgSubCode.poke(MsgSubCode.Active)
        c.io.data0.poke(5.U)
        c.io.data1.poke(9.U)

        while (!c.io.phase0Val.peek().litToBoolean) {
          c.clock.step()
        }
        println(s"msgOut at phase 0: ${c.io.msgOut.peek().litValue}")

        while (!c.io.phase1Val.peek().litToBoolean) {
          c.clock.step()
        }
        println(s"msgOut at phase 1: ${c.io.msgOut.peek().litValue}")

        while (!c.io.done.peek().litToBoolean) {
          c.clock.step()
        }
        c.io.phase2Val.expect(false.B)
        c.io.phase3Val.expect(false.B)
      }
    }
  }
}