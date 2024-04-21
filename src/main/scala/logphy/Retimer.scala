package edu.berkeley.cs.ucie.digital
package logphy

import chisel3.stage.ChiselStage // Don't move this line, or it doesnt work
import chisel3._
import chisel3.util._
import interfaces._
import sideband._
import afe._
// Credit-based flow control is only required for in-package 
// For now, assume direct transmission for off package.

class Retimer (lanes: Int = 16, afeParams: AfeParams){
    val io = IO(new Bundle {
        val spio =  Flipped(new StandardPackageIo(lanes))
        val sbAfe = new SidebandAfeIo(afeParams)
        val mbAfe = new MainbandAfeIo(afeParams)
    })
    //
}

// For now use digital, take care of analog later
class RetimerDigital (lanes: Int = 16, bufferSizeIn256B: Int = 4){
    val io = IO(new Bundle {
        val spio_in =  Flipped(new StandardPackageIo(lanes))
        // spio_out is a placeholder to mimic remote link transfer.
        val spio_out = new StandardPackageIo(lanes)
        val reset = Input(Bool())
    })
    val bufferEntrySize = lanes * 8 // size entry in bit

    val uiMax = 256 * 8 / lanes // #UIs to process before sending a credit back
    val receiverBuffer = new Queue(Bits(bufferEntrySize.W), bufferSizeIn256B * 8 * 256 / bufferEntrySize)
    // Test with a single clock first, then double clock
    val buffer16B = Reg(Vec(16, UInt(8.W)))
    val uiCounter = Reg(0.U((log2Ceil(lanes)+1).W))
    val inMainband = io.spio_in.tx.mainband

    withReset(io.reset) { // Explicitly specifying reset condition
        buffer16B.foreach(r => r := 0.U)
    }

    withClock(inMainband.clkp) {
        for(i <- 0 until lanes) {
            buffer16B(i) := buffer16B(i) << 1.U | inMainband.data(i)
        }
        // when(uiCounter === 7.U){
        //     printf()
        // }
    }
    // declare a receiver buffer = 
    // bufferSizeIn256B * 256 entries of 8 bit number
    // Need a front buffer to temporarily store 16 * 8 UI data, then store into receiver buffer


    // die - data > retimer via spio_in.tx
    // die - data_valid > retimer via spio_in.tx
    // retimer - credit/valid > die via spio_in.rx
    // retimer - data from remote partner > die via spio_in.rx

    // Need a receiver buffer
    
}


// class RetimerReceive(lanes: Int = 16, bufferSizeIn256B: Int = 4, mbSerializerRatio: Int = 16) extends Module{
//     val io = IO(new Bundle {
//         val inData = Input(Bits(lanes.W))
//         val startEnq = Input(Bool())
//         val startDeq = Input(Bool())
//         // val OutClk = Input(Clock())
//         // val outData = Output(Bits((lanes * 8).W))
//         val outData = Output(Bits(lanes.W))
//     })
//     val buffer16B = RegInit(VecInit(Seq.fill(lanes)(0.U(8.W))))
//     val uiInCounter = RegInit(0.U(3.W))
//     val uiInCounter_next = RegNext(uiInCounter)

//     val uiMax = 256 / lanes // #UIs to process before sending a credit back
//     val receiverBuffer = Module(new Queue(Bits((lanes * 8).W), bufferSizeIn256B * 256 / lanes)) 
//     val writeCounter = RegInit(0.U((bufferSizeIn256B * 256 / lanes).W))
//     val startEnqReg = RegNext(io.startEnq)
//     val startDeqReg = RegNext(io.startDeq)

//     val inDataReg = RegNext(io.inData)
//     receiverBuffer.io.enq.valid := false.B
//     receiverBuffer.io.enq.bits := 0.U
    

//     when(startEnqReg){
//         when(uiInCounter_next === 7.U) {
//             receiverBuffer.io.enq.valid := true.B
//             receiverBuffer.io.enq.bits := Cat(buffer16B.reverse)
//         }

//         when(uiInCounter === 0.U) {
//             for(i <- 0 until lanes) {
//                 buffer16B(i) := 0.U | inDataReg(i)
//             }

//         }.otherwise {
//             for(i <- 0 until lanes) {
//                 buffer16B(i) := buffer16B(i) << 1.U | inDataReg(i)
//             } 
//         }
//         uiInCounter := uiInCounter + 1.U
//     }

//     val uiOutCounter = RegInit(0.U(3.W))
//     val uiOutCounter_next = RegNext(uiOutCounter)

//     // val deqReg = RegInit(0.U((lanes * 8).W))
//     val deqReg = RegInit(VecInit(Seq.fill(lanes)(0.U(8.W))))

//     receiverBuffer.io.deq.ready := false.B
//     // withClock(io.OutClk){


//     when(startDeqReg){
//         when(uiOutCounter === 0.U) {
//             receiverBuffer.io.deq.ready := true.B
//             (0 until lanes).foreach { i =>
//                 deqReg(i) := receiverBuffer.io.deq.bits(8 * (i + 1) - 1, 8 * i)
//             }
//         }.otherwise{
//             deqReg.foreach{ byte =>
//                 byte := byte << 1.U
//             }
//         }
//         uiOutCounter := uiOutCounter + 1.U
//     }

//     io.outData := VecInit(deqReg.map(_.head(1))).asUInt



//     // Instantiating a custom async fifo, works
//     val fifo = Module(new AsyncFifoStefan(16, 16))
//     fifo.io.data_w := 0.U
//     fifo.io.valid_w := false.B
//     fifo.io.ready_r := false.B 
//     fifo.io.rst := true.B 
//     fifo.io.clk_r := false.B 
//     fifo.io.clk_w := false.B 

//     // Instantiating the standard async fifo, works
//     val asyncfifo = Module(new AsyncQueue(Bits(16.W), AsyncQueueParams()))
//     asyncfifo.io.enq_clock := clock
//     asyncfifo.io.enq_reset :=false.B
//     asyncfifo.io.enq.bits  := 0.U
//     asyncfifo.io.enq.valid :=false.B
//     // io.enq.ready           :=false.B
//     asyncfifo.io.deq_clock := clock
//     asyncfifo.io.deq_reset :=false.B
//     // io.deq.bits := 0.U
//     // io.deq.valid :=false.B
//     asyncfifo.io.deq.ready :=false.B
//     // }

// }

// // object GenerateVerilog extends App{
// //     ChiselStage.emitSystemVerilog(new RetimerReceive())
// // }
// object Verilog extends App {
//   (new ChiselStage).emitSystemVerilog(new RetimerReceive()
//   )
// }