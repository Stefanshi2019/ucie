package edu.berkeley.cs.ucie.digital

import chisel3._
import chisel3.util._
import chiseltest._
import org.scalatest.funspec.AnyFunSpec
import org.scalatest.flatspec.AnyFlatSpec
import interfaces._
import sideband._
import logphy._






class RetimerReceive(lanes: Int = 16, bufferSizeIn256B: Int = 4, mbSerializerRatio: Int = 16) extends Module{
    val io = IO(new Bundle {
        val inData = Input(Bits(lanes.W))
        val startEnq = Input(Bool())
        val startDeq = Input(Bool())
        // val OutClk = Input(Clock())
        // val outData = Output(Bits((lanes * 8).W))
        val outData = Output(Bits(lanes.W))
    })
    val buffer16B = RegInit(VecInit(Seq.fill(lanes)(0.U(8.W))))
    val uiInCounter = RegInit(0.U(3.W))
    val uiInCounter_next = RegNext(uiInCounter)

    val uiMax = 256 / lanes // #UIs to process before sending a credit back
    val receiverBuffer = Module(new Queue(Bits((lanes * 8).W), bufferSizeIn256B * 256 / lanes)) 
    val writeCounter = RegInit(0.U((bufferSizeIn256B * 256 / lanes).W))
    val startEnqReg = RegNext(io.startEnq)
    val startDeqReg = RegNext(io.startDeq)

    val inDataReg = RegNext(io.inData)
    receiverBuffer.io.enq.valid := false.B
    receiverBuffer.io.enq.bits := 0.U
    

    when(startEnqReg){
        when(uiInCounter_next === 7.U) {
            receiverBuffer.io.enq.valid := true.B
            receiverBuffer.io.enq.bits := Cat(buffer16B.reverse)
        }

        when(uiInCounter === 0.U) {
            for(i <- 0 until lanes) {
                buffer16B(i) := 0.U | inDataReg(i)
            }

        }.otherwise {
            for(i <- 0 until lanes) {
                buffer16B(i) := buffer16B(i) << 1.U | inDataReg(i)
            } 
        }
        uiInCounter := uiInCounter + 1.U
    }

    val uiOutCounter = RegInit(0.U(3.W))
    val uiOutCounter_next = RegNext(uiOutCounter)

    // val deqReg = RegInit(0.U((lanes * 8).W))
    val deqReg = RegInit(VecInit(Seq.fill(lanes)(0.U(8.W))))

    receiverBuffer.io.deq.ready := false.B
    // withClock(io.OutClk){


    when(startDeqReg){
        when(uiOutCounter === 0.U) {
            receiverBuffer.io.deq.ready := true.B
            (0 until lanes).foreach { i =>
                deqReg(i) := receiverBuffer.io.deq.bits(8 * (i + 1) - 1, 8 * i)
            }
        }.otherwise{
            deqReg.foreach{ byte =>
                byte := byte << 1.U
            }
        }
        uiOutCounter := uiOutCounter + 1.U
    }

    // }
    io.outData := VecInit(deqReg.map(_.head(1))).asUInt
}

// class RetimerSend (lanes: Int = 16, bufferSizeIn256B: Int = 4) extends Module {
//     io = IO(new Bundle{
        
//     })

// }


class PlaygroundTestbench extends AnyFunSpec with ChiselScalatestTester {
    describe("Playground Testbench") {
        it("Testing out submodule functionalities.") {
        test(
        new RetimerReceive()
        ).withAnnotations(Seq(WriteVcdAnnotation)) { c =>
            // Initialize inputs
            // c.clock.step()
            // c.io.inData.poke("hE".U)
            // c.clock.step()
            c.io.startEnq.poke(true.B)
            c.io.startDeq.poke(false.B)

            var temp = 15
            for(i <- 0 until 8) {
                c.io.inData.poke("h000F".U)
                c.clock.step()
            }
            for(i <- 0 until 8) {
                c.io.inData.poke("h00F0".U)
                c.clock.step()
            }
            for(i <- 0 until 8) {
                c.io.inData.poke("h0F00".U)
                c.clock.step()
            }
            for(i <- 0 until 8) {
                c.io.inData.poke("hF000".U)
                c.clock.step()
            }

            c.clock.step()
            c.clock.step()
            c.io.startEnq.poke(false.B)
            c.io.startDeq.poke(true.B)

 
            for(i <- 0 until 33) {
                c.clock.step()
                println(s"${c.io.outData.peek().litValue}")
            }
            c.clock.step()
            c.clock.step()
        } 
        }
    }
}