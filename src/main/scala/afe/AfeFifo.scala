package edu.berkeley.cs.ucie.digital
package afe

import chisel3._
// import chisel3.util._ 
import chisel3.stage.ChiselStage
import freechips.asyncqueue._
import interfaces._


class AfeFifo (depth: Int, width: Int, version: Int) extends Module {
    val io = IO(new Bundle {
        val mbAfeIo = new MainbandAfeIo(AfeParams())
        val sbAfeIo = new SidebandAfeIo(AfeParams())
        val stdIo = new StandardPackageIo()
        // The following differential clock comes from pll
        val clkp = Input(Clock())
        val clkn = Input(Clock())
        val clk800 = Input(Clock())
    })

    val queueParams = AsyncQueueParams(
        depth = depth,   // Custom depth
        sync = 3,     // Custom synchronization stages
        safe = true,  // Use safe reset
        narrow = false // Use wide configuration
    )

    val txMbIo = io.stdIo.tx.mainband
    val rxMbIo = io.stdIo.rx.mainband
    val txSbIo = io.stdIo.tx.sideband
    val rxSbIo = io.stdIo.rx.sideband

    // Decoupled data
    val txMbAfeData = io.mbAfeIo.txData
    val rxMbAfeData = io.mbAfeIo.rxData
    val txSbAfeData = io.sbAfeIo.txData
    val rxSbAfeData = io.sbAfeIo.rxData
 
    // class MainbandIo(lanes: Int = 16) extends Bundle {
    //     val data = Bits(lanes.W)
    //     val valid = Bool()
    //     val track = Bool()
    //     val clkp = Clock()
    //     val clkn = Clock()
    // }
    // /** The sideband pins exposed by a standard package UCIe module in one
    //  * direction.
    //  */
    // class SidebandIo extends Bundle {
    //     val data = Bool()
    //     val clk = Clock()
    // }


    // The following assignments rely on consistent mainband bandwidth = 16
    // Serdes required otherwise
    // Currently only fifo is needed for cdc,
    val txMbFifo = Module(new AsyncQueue(Bits(width.W), queueParams))
    txMbFifo.io.enq_clock := clock //enq is from afe, use system clock
    txMbFifo.io.enq_reset := reset // use system reset
    txMbFifo.io.enq.bits  := txMbAfeData.bits
    txMbFifo.io.enq.valid := txMbAfeData.valid
    txMbAfeData.ready           := txMbFifo.io.enq.ready

    txMbFifo.io.deq_clock := io.clkp
    txMbFifo.io.deq_reset := reset
    txMbIo.data := txMbFifo.io.deq.bits
    txMbIo.valid := txMbFifo.io.deq.valid
    txMbFifo.io.deq.ready := true.B


    val rxMbFifo = Module(new AsyncQueue(Bits(width.W), queueParams))
    rxMbFifo.io.enq_clock := rxMbIo.clkp
    rxMbFifo.io.enq_reset := reset
    rxMbFifo.io.enq.bits := rxMbIo.data
    rxMbFifo.io.enq.valid := rxMbIo.valid
    // leave rxMbFifo.io.enq floating
    // If fifo full -> ready == 0, data will keep transferring and lost on the way
    rxMbFifo.io.deq_clock := clock
    rxMbFifo.io.deq_reset := reset
    rxMbAfeData.bits := rxMbFifo.io.deq.bits
    rxMbAfeData.valid := rxMbFifo.io.deq.valid 
    rxMbFifo.io.deq.ready := rxMbAfeData.ready

    // // Sideband to be implemented later
    // val txSbIo = io.stdIo.tx.sideband
    // val rxSbIo = io.stdIo.rx.sideband

    // val txSbAfeData = io.sbAfeIo.txData
    // val rxSbAfeData = io.sbAfeIo.rxData 
    
    // txSbIo.clk := io.clk800
    // txSbIo.data := txMbAfeData.bits
    

    // val txSbFifo =  Module(new AsyncQueue(Bits(1.W), queueParams))
    
    // // Use clkp only for now
    // // later implement an arbitor for clkp/clkn
    // val txMbfifo = if(version == 0){
    //     Module(new AsyncQueue(Bits(width.W), queueParams))
    // } else {
    //     // val fifo = Module(new AsyncFifoStefan(depth, width))
    //     // fifo.io.data_w := 0.U
    //     // fifo.io.valid_w := false.B
    //     // fifo.io.ready_r := false.B 
    //     // fifo.io.rst := true.B 
    //     // fifo.io.clk_r := false.B 
    //     // fifo.io.clk_w := false.B 
    // }

    // if(verssion == 0){
    //     txMbfifo.io.enq_clock := clock //enq is from afe, use system clock
    //     txMbfifo.io.enq_reset := reset // use system reset
    //     txMbfifo.io.enq.bits  := txAfeData.bits
    //     txMbfifo.io.enq.valid := txAfeData.valid
    //     txAfeData.ready           := txMbfifo.io.enq.ready

    //     txMbfifo.io.deq_clock := io.clkp
    //     txMbfifo.io.deq_reset := reset
    //     txMbIo.data := txMbfifo.io.deq.bits
    //     txMbIo.valid := txMbfifo.io.deq.valid
    //     txMbfifo.io.deq.ready := true.B // No back pressure on mainband, assume ready always on?
    // }


    // val rxMbfifo = if(version == 0){
    //     Module(new AsyncQueue(Bits(width.W), queueParams))

    //     // asyncfifo.io.enq_clock := io.clkp //enq is from afe, use system clock
    //     // asyncfifo.io.enq_reset := reset // use system reset
    //     // asyncfifo.io.enq.bits  := rxMbIo.data
    //     // asyncfifo.io.enq.valid := rxMbIo.valid
    //     // txAfeData.ready           := asyncfifo.io.enq.ready

    //     // asyncfifo.io.deq_clock := clock
    //     // asyncfifo.io.deq_reset := reset
    //     // rxMbIo := asyncfifo.io.deq.bits
    //     // txMbIo.valid := asyncfifo.io.deq.valid
    //     // asyncfifo.io.deq.ready := true.B // No back pressure on mainband, assume ready always on?
    // } else {
    //     val fifo = Module(new AsyncFifoStefan(depth, width))
    //     fifo.io.data_w := 0.U
    //     fifo.io.valid_w := false.B
    //     fifo.io.ready_r := false.B 
    //     fifo.io.rst := true.B 
    //     fifo.io.clk_r := false.B 
    //     fifo.io.clk_w := false.B 
    // } 

}

object Verilog extends App {
    (new ChiselStage).emitSystemVerilog(new AfeFifo(16, 16, 0)
    )
}