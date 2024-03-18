package edu.berkeley.cs.ucie.digital
package afe

import chisel3._
import chisel3.util._ 
import chisel3.stage.ChiselStage
import freechips.asyncqueue._
import interfaces._
import chisel3.experimental.DataMirror

// This module receives data from adapter and sends to analog
class TxMainband(depth: Int, width: Int, version: Int, lanes: Int = 16, BYTE: Int = 8) extends Module {
    val io = IO(new Bundle {
        // should use rx of mbafeIo
        val mbAfeIo = new MainbandAfeIo(AfeParams())
        val txMbIo = Output(new MainbandIo())

        val clkp = Input(Clock())
        val clkn = Input(Clock())
        val track = Input(Bool())        
        // Dummy signals for testing
        val startDeq = Input(Bool())
        val startEnq = Input(Bool())
    })
    io.mbAfeIo.txData.bits := Seq.fill(lanes)(0.U)
    io.mbAfeIo.txData.valid := false.B 
    io.txMbIo.clkn := io.clkn
    io.txMbIo.clkp := io.clkp 
    io.txMbIo.track := false.B

    val startDeqReg = RegNext(io.startDeq)
    val startEnqReg = RegNext(io.startEnq)

    val queueParams = AsyncQueueParams(
        depth = depth,   // Custom depth
        sync = 3,     // Custom synchronization stages
        safe = true,  // Use safe reset
        narrow = false // Use wide configuration
    ) 
    // receive data
    val rxMbAfeData = io.mbAfeIo.rxData
    
    val txMbFifos = Seq.fill(lanes)(Module (new AsyncQueue(Bits(BYTE.W), queueParams)))
    val txMbShiftRegs = Seq.fill(lanes)(RegInit(0.U(BYTE.W)))
    val txMbUICounter = RegInit(0.U(log2Ceil(BYTE).W))
    // Assign each async fifo individually
    txMbFifos.zipWithIndex.foreach{ case (txMbFifo, i) =>
        // Enqueue end from adapter
        txMbFifo.io.enq_clock := clock //enq is from afe, use system clock
        txMbFifo.io.enq_reset := reset // use system reset
        txMbFifo.io.enq.bits  := rxMbAfeData.bits(i)
        txMbFifo.io.enq.valid := rxMbAfeData.valid

        // Dequeue end to analog
        withClock(io.clkp){
            txMbFifo.io.deq_clock := io.clkp
            txMbFifo.io.deq_reset := reset
            io.txMbIo.valid := txMbFifo.io.deq.valid
            txMbFifo.io.deq.ready := false.B

            when(startDeqReg){
                when(txMbUICounter === 0.U) {
                    txMbFifo.io.deq.ready := true.B
                    txMbShiftRegs(i) := txMbFifo.io.deq.bits
                }.otherwise{
                    txMbShiftRegs(i) := txMbShiftRegs(i) << 1.U
                }
                txMbUICounter := txMbUICounter + 1.U
            }
        }
    }
    io.txMbIo.data := VecInit(txMbShiftRegs.map(_.head(1))).asUInt
    rxMbAfeData.ready := txMbFifos.map(_.io.enq.ready).reduce(_ && _) 

}


// This module accepts data from analog and send to adapter
class RxMainband(depth: Int, width: Int, version: Int, lanes: Int = 16, BYTE: Int = 8) extends Module {
    val io = IO(new Bundle {
        // should use rx of mbafeIo
        val mbAfeIo = new MainbandAfeIo(AfeParams())
        val rxMbIo = Input(new MainbandIo())

        val clkp = Input(Clock())
        val clkn = Input(Clock())
        val clk800 = Input(Clock())
        // Dummy signals for testing
        val startDeq = Input(Bool())
        val startEnq = Input(Bool())
    })
    val queueParams = AsyncQueueParams(
        depth = depth,   // Custom depth
        sync = 3,     // Custom synchronization stages
        safe = true,  // Use safe reset
        narrow = false // Use wide configuration
    ) 
    // Since sending data to adapter,
    // This module Should drive mbAfeIo tx data
    val txMbAfeData = io.mbAfeIo.txData

    // This module receives data from analog, and store into async buffer
    val rxMbFifos = Seq.fill(lanes)(Module (new AsyncQueue(Bits(BYTE.W), queueParams)))
    // Shiftregs to deserialize and store into the async buffer
    val rxMbShiftRegs = Seq.fill(lanes)(RegInit(0.U(BYTE.W)))
    val rxMbUICounter = RegInit(0.U(log2Ceil(BYTE).W))
    val rxMbUICounter_next = RegNext(rxMbUICounter)

    val rxMbIoData_next = RegNext(io.rxMbIo.data)

    val startEnqReg = RegNext(io.startEnq)
    rxMbFifos.zipWithIndex.foreach{ case(rxMbFifo, i) =>
        // Enqueue end from analog
        withClock(io.rxMbIo.clkp) {
            rxMbFifo.io.enq_clock := io.rxMbIo.clkp 
            rxMbFifo.io.enq_reset := reset
            rxMbFifo.io.enq.valid := false.B
            // For clear testing visuals, should always connect to signal path for minimal delay
            rxMbFifo.io.enq.bits := 0.U
            // rxMbFifo.io.enq.bits := Cat(rxMbShiftRegs.reverse)
            when(startEnqReg){
                when(rxMbUICounter_next === 7.U) {
                    rxMbFifo.io.enq.valid := true.B
                    rxMbFifo.io.enq.bits := Cat(rxMbShiftRegs.reverse)
                }

                when(rxMbUICounter === 0.U) {
                    for(i <- 0 until lanes) {
                        rxMbShiftRegs(i) := 0.U | rxMbIoData_next(i)
                    }

                }.otherwise {
                    for(i <- 0 until lanes) {
                        rxMbShiftRegs(i) := rxMbShiftRegs(i) << 1.U | rxMbIoData_next(i)
                    } 
                }
                rxMbUICounter := rxMbUICounter + 1.U
            }
        }
        
        // Dequeue end to drive 
        rxMbFifo.io.deq_clock := clock
        rxMbFifo.io.deq_reset := reset
        txMbAfeData.bits := rxMbFifo.io.deq.bits
        txMbAfeData.valid := rxMbFifo.io.deq.valid 
        rxMbFifo.io.deq.ready := txMbAfeData.ready

    }
}


class AfeFifo (depth: Int, width: Int, version: Int) extends Module {
    val io = IO(new Bundle {
        val mbAfeIo = new MainbandAfeIo(AfeParams())
        val sbAfeIo = new SidebandAfeIo(AfeParams())
        val stdIo = new StandardPackageIo()
        // The following differential clock comes from pll
        val clkp = Input(Clock())
        val clkn = Input(Clock())
        val clk800 = Input(Clock())
        // Dummy signals for testing
        val startDeq = Input(Bool())
        val startEnq = Input(Bool())
    })

    val startDeqReg = RegNext(io.startDeq)

    val lanes = AfeParams().mbLanes
    val BYTE = 8
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
 
    val txMbFifos = Seq.fill(lanes)(Module (new AsyncQueue(Bits(BYTE.W), queueParams)))
    val txMbShiftRegs = Seq.fill(lanes)(RegInit(0.U(BYTE.W)))
    val txMbUICounter = RegInit(0.U(log2Ceil(lanes).W))

    // Assign each async fifo individually
    txMbFifos.zipWithIndex.foreach{ case (txMbFifo, i) =>
        txMbFifo.io.enq_clock := clock //enq is from afe, use system clock
        txMbFifo.io.enq_reset := reset // use system reset
        txMbFifo.io.enq.bits  := rxMbAfeData.bits(i)
        txMbFifo.io.enq.valid := rxMbAfeData.valid

        withClock(io.clkp){
            txMbFifo.io.deq_clock := io.clkp
            txMbFifo.io.deq_reset := reset
            txMbIo.valid := txMbFifo.io.deq.valid
            txMbFifo.io.deq.ready := false.B

            when(startDeqReg){
                when(txMbUICounter === 0.U) {
                    txMbFifo.io.deq.ready := true.B
                    txMbShiftRegs(i) := txMbFifo.io.deq.bits
                }.otherwise{
                    txMbShiftRegs(i) := txMbShiftRegs(i) << 1.U
                }
                txMbUICounter := txMbUICounter + 1.U
            }
        }
    }
    txMbIo.data := VecInit(txMbShiftRegs.map(_.head(1))).asUInt
    rxMbAfeData.ready := txMbFifos.map(_.io.enq.ready).reduce(_ && _) 




    val rxMbFifos = Seq.fill(lanes)(Module (new AsyncQueue(Bits(BYTE.W), queueParams)))
    val rxMbShiftRegs = Seq.fill(lanes)(RegInit(0.U(BYTE.W)))
    val rxMbUICounter = RegInit(0.U(log2Ceil(lanes).W))
    val rxMbUICounter_next = RegNext(rxMbUICounter)

    val rxMbIoData_next = RegNext(rxMbIo.data)

    val startEnqReg = RegNext(io.startEnq)
    rxMbFifos.zipWithIndex.foreach{ case(rxMbFifo, i) =>
        withClock(rxMbIo.clkp) {
            rxMbFifo.io.enq_clock := rxMbIo.clkp 
            rxMbFifo.io.enq_reset := reset
            rxMbFifo.io.enq.valid := false.B
            // For clear testing visuals, should always connect to signal path for minimal delay
            rxMbFifo.io.enq.bits := 0.U
            // rxMbFifo.io.enq.bits := Cat(rxMbShiftRegs.reverse)
            when(startEnqReg){
                when(rxMbUICounter_next === 7.U) {
                    rxMbFifo.io.enq.valid := true.B
                    rxMbFifo.io.enq.bits := Cat(rxMbShiftRegs.reverse)
                }

                when(rxMbUICounter === 0.U) {
                    for(i <- 0 until lanes) {
                        rxMbShiftRegs(i) := 0.U | rxMbIoData_next(i)
                    }

                }.otherwise {
                    for(i <- 0 until lanes) {
                        rxMbShiftRegs(i) := rxMbShiftRegs(i) << 1.U | rxMbIoData_next(i)
                    } 
                }
                rxMbUICounter := rxMbUICounter + 1.U
            }
        }
        
        rxMbFifo.io.deq_clock := clock
        rxMbFifo.io.deq_reset := reset
        rxMbAfeData.bits := rxMbFifo.io.deq.bits
        rxMbAfeData.valid := rxMbFifo.io.deq.valid 
        rxMbFifo.io.deq.ready := rxMbAfeData.ready

    }
    // Dummy to pass compilation for now
    txMbAfeData.bits := Seq.fill(lanes)(0.U)
    txSbAfeData.bits := 0.U
    txMbIo.clkn := io.clkn 
    txMbIo.clkp := io.clkp 
    txSbIo.clk := io.clk800 
    txMbIo.track := 0.U 
    txSbIo.data := 0.U 
    txMbAfeData.valid := true.B
    io.sbAfeIo.rxEn := 0.U 
    io.sbAfeIo.rxData.ready := 0.U 
    io.sbAfeIo.txData.valid := 0.U
    io.sbAfeIo.pllLock := 0.U


    // val txMbFifo = Module(new AsyncQueue(Bits(width.W), queueParams))
    // txMbAfeData.ready := true.B 

    //// Still valid, to be implemented later
    // val rxMbFifo = Module(new AsyncQueue(Bits(width.W), queueParams))
    // rxMbFifo.io.enq_clock := rxMbIo.clkp
    // rxMbFifo.io.enq_reset := reset
    // rxMbFifo.io.enq.bits := rxMbIo.data
    // rxMbFifo.io.enq.valid := rxMbIo.valid
    // // leave rxMbFifo.io.enq floating
    // // If fifo full -> ready == 0, data will keep transferring and lost on the way
    // rxMbFifo.io.deq_clock := clock
    // rxMbFifo.io.deq_reset := reset
    // rxMbAfeData.bits := rxMbFifo.io.deq.bits
    // rxMbAfeData.valid := rxMbFifo.io.deq.valid 
    // rxMbFifo.io.deq.ready := rxMbAfeData.ready

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

// To execute do:
// runMain edu.berkeley.cs.ucie.digital.afe.TxMainbandVerilog 
object TxMainbandVerilog extends App {
    (new ChiselStage).emitSystemVerilog(new TxMainband(16, 16, 0)
    )
}