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
    io.done := sme.io.done

    io.sbAfe.txData.bits := io.msgOut
    io.sbAfe.txData.valid := io.enable && !io.done
    io.sbAfe.pllLock := 1.U
    io.sbAfe.rxEn := 0.U
    io.sbAfe.rxData.ready := 0.U
    // io.sbAfe.txData.irdy := io.sbAfe.txData.valid   
}

class receiver () extends Module{
    val io = IO(new Bundle{
        val ready = Input(Bool())
        val in = Flipped(Decoupled3(Bits(32.W)))
        val bits = Output(Bits(32.W))
    })
    io.in.ready := io.ready
    printf(p"${io.in}\n")
    io.bits := io.in.bits
}

class DummyModuleTest extends AnyFunSpec with ChiselScalatestTester {
  describe("DUT Module with Decoupled3 Interface") {
    it("should properly transfer data from sender to receiver when ready and valid") {
      test(new dut2(AfeParams())) { c =>
        // Initialize inputs
        c.io.enable.poke(true.B)  // Example data to send
        
        c.io.msgHeader.srcid.poke(1.U)
        c.io.msgHeader.rsvd_00.poke(1.U)
        c.io.msgHeader.rsvd_01.poke(1.U)
        c.io.msgHeader.msgCode.poke(1.U)
        c.io.msgHeader.rsvd_02.poke(1.U)
        c.io.msgHeader.opcode.poke(1.U)
        // Phase 1
        c.io.msgHeader.dp.poke(1.U)
        c.io.msgHeader.cp.poke(1.U)
        c.io.msgHeader.rsvd_10.poke(1.U)
        c.io.msgHeader.dstid.poke(1.U)
        c.io.msgHeader.msgInfo.poke(1.U)
        c.io.msgHeader.msgSubCode.poke(1.U)
        c.io.data0.poke(5.U)
        c.io.data1.poke(9.U)
        

        // // Chisel testbench does not allow .asUInt
        // c.io.msgHeader.srcid.poke(SourceID.DieToDieAdapter.asUInt)
        // c.io.msgHeader.rsvd_00.poke(1.U)
        // c.io.msgHeader.rsvd_01.poke(1.U)
        // c.io.msgHeader.msgCode.poke(MsgCode.LinkMgmt_RDI_Req.asUInt)
        // c.io.msgHeader.rsvd_02.poke(1.U)
        // c.io.msgHeader.opcode.poke(PacketType.MessageWithoutData.asUInt)
        // // Phase 1
        // c.io.msgHeader.dp.poke(1.U)
        // c.io.msgHeader.cp.poke(1.U)
        // c.io.msgHeader.rsvd_10.poke(1.U)
        // c.io.msgHeader.dstid.poke(1.U)
        // c.io.msgHeader.msgInfo.poke(MsgInfo.RegularResponse.asUInt)
        // c.io.msgHeader.msgSubCode.poke(MsgSubCode.Active.asUInt)
        // c.io.data0.poke(5.U)
        // c.io.data1.poke(9.U)

        // Step the clock to observe the transfer
        c.clock.step(1)
        println(s"msgOut: ${c.io.msgOut.peek().litValue}, done: ${c.io.done.peek().litToBoolean}")
        // c.io.msgOut.peek()
        // c.io.done.peek()
        c.clock.step(5)
        println(s"msgOut: ${c.io.msgOut.peek().litValue}, done: ${c.io.done.peek().litToBoolean}")

        // c.io.msgOut.peek()
        // c.io.done.peek()
        c.io.enable.poke(false.B)
        c.clock.step(1)
        println(s"msgOut: ${c.io.msgOut.peek().litValue}, done: ${c.io.done.peek().litToBoolean}")

        // c.io.msgOut.peek()
        // c.io.done.peek() 
      }
    }
  }
}