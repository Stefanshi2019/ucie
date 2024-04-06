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
        // val output_clkp = Output(Clock())
        // Dummy signals for testing
        val startDeq = Input(Bool())
    })
    io.mbAfeIo.txData.bits := Seq.fill(lanes)(0.U)
    io.mbAfeIo.txData.valid := false.B 


    
    val startDeqReg = RegNext(io.startDeq)

    val queueParams = AsyncQueueParams(
        depth = depth,   // Custom depth
        sync = 3,     // Custom synchronization stages
        safe = true,  // Use safe reset
        narrow = false // Use wide configuration
    ) 
    // receive data
    val rxMbAfeData = io.mbAfeIo.rxData

    // Default fifo has problem: when deq starts, data is XXXX for at least 10 cycles, not sure why
    // Use custom async fifo, this one works
    // val txMbFifos = Seq.fill(lanes)(Module (new AsyncQueue(Bits(BYTE.W), queueParams)))
    val txMbFifos = Seq.fill(lanes)(Module (new AsyncFifoStefan(depth, BYTE)))
    withClock(io.clkp) {
        val outValid = Wire(Bool())
        val outTrack = Wire(Bool())
        val txMbShiftRegs = Seq.fill(lanes)(RegInit(0.U(BYTE.W)))
        val txMbUICounter = RegInit(0.U(log2Ceil(BYTE).W))
        // val txMbUICounter_next = RegNext(txMbUICounter, (BYTE/2-1).U) // To synchronize mbio valid signal
        val txMbUICounter_next = RegNext(txMbUICounter, 0.U) // To synchronize mbio valid signal
        val hasData = Wire(Bool())
        val clockGateCounter = RegInit(0.U(log2Ceil(16).W))
        val fifoValid_next = RegNext(txMbFifos.map(_.io.deq.valid).reduce(_ && _))
        val shift = RegInit(false.B(Bool()))
        val outValid_next = RegNext(outValid)
        hasData := ~(txMbFifos.map(_.io.deq.valid).reduce(_ && _) ^ fifoValid_next ) & fifoValid_next 
        when(outValid){
            clockGateCounter := 0.U
        }.elsewhen(~outValid && clockGateCounter < 12.U) {
            clockGateCounter := clockGateCounter + 1.U
        }

        io.txMbIo.clkn := Mux((clockGateCounter >= 12.U && ~outValid), false.B, io.clkn.asBool).asClock
        io.txMbIo.clkp := Mux((clockGateCounter >= 12.U && ~outValid), false.B, io.clkp.asBool).asClock
        // io.output_clkp := Mux((clockGateCounter >= 8.U && ~outValid), 0.U, io.clkp.asUInt)
        // Assign each async fifo individually
        txMbFifos.zipWithIndex.foreach{ case (txMbFifo, i) =>
            // Enqueue end from adapter
            txMbFifo.io.enq_clock := clock //enq is from afe, use system clock
            txMbFifo.io.enq_reset := reset // use system reset
            txMbFifo.io.enq.bits  := rxMbAfeData.bits(i)
            txMbFifo.io.enq.valid := rxMbAfeData.valid

            // Dequeue end to analog
            txMbFifo.io.deq_clock := io.clkp
            txMbFifo.io.deq_reset := reset
            txMbFifo.io.deq.ready := false.B
            // Valid framing, up for first 4 ui, down for last 4 ui
            // outValid := false.B 
            outTrack := false.B
            when( txMbUICounter_next =/= txMbUICounter && txMbUICounter_next <= (BYTE/2-1).U){
                outValid := true.B
            }.otherwise{
                outValid := false.B
            }
            when(hasData){
                when(txMbUICounter === 0.U) {
                    txMbFifo.io.deq.ready := true.B
                    txMbShiftRegs(i) := txMbFifo.io.deq.bits
                }.otherwise{
                    txMbShiftRegs(i) := txMbShiftRegs(i) << 1.U
                }
                txMbUICounter := txMbUICounter + 1.U
            }
            when(txMbUICounter === 0.U && ~hasData){
                txMbUICounter := 0.U
                shift := false.B
            }.otherwise{
                txMbUICounter := txMbUICounter + 1.U
                shift := true.B
            }
            when(shift){
                when(txMbUICounter === 0.U) {
                    txMbFifo.io.deq.ready := true.B
                    txMbShiftRegs(i) := txMbFifo.io.deq.bits
                }.otherwise{
                    txMbShiftRegs(i) := txMbShiftRegs(i) << 1.U
                }
            }
        }
        io.txMbIo.data := VecInit(txMbShiftRegs.map(_.head(1))).asUInt
        rxMbAfeData.ready := txMbFifos.map(_.io.enq.ready).reduce(_ && _) 

        io.txMbIo.track := outTrack
        io.txMbIo.valid := outValid
    }

    
    withClock(clock) {
        txMbFifos.zipWithIndex.foreach{ case (txMbFifo, i) =>
            // Enqueue end from adapter
            txMbFifo.io.enq_clock := clock //enq is from afe, use system clock
            txMbFifo.io.enq_reset := reset // use system reset
            txMbFifo.io.enq.bits  := rxMbAfeData.bits(i)
            txMbFifo.io.enq.valid := rxMbAfeData.valid
    
        }
    }
}

class RxSideband(depth:Int, width: Int = 1, afeParams: AfeParams = AfeParams()) extends Module {
    val io = IO(new Bundle{
        // val sbAfeIo = new SidebandAfeIo(AfeParams())
        val txSbAfe = Decoupled(Bits(afeParams.sbSerializerRatio.W))
        val clk_800 = Input(Clock())
        val rxSbIo = Input(new SidebandIo())
    })
    private val bw = afeParams.sbSerializerRatio
    // private val bw = 8
    val fifo = Module(new AsyncFifoStefan(depth, bw)) 
    // val txMbFifos = Seq.fill(lanes)(Module (new AsyncFifoStefan(depth, BYTE)))
    io.txSbAfe.valid := fifo.io.deq.valid 
    io.txSbAfe.bits := fifo.io.deq.bits
    fifo.io.deq.ready := io.txSbAfe.ready
    fifo.io.deq_clock := clock
    fifo.io.deq_reset := reset
    
    withClock(io.clk_800){
        val shiftReg = RegInit(0.U(bw.W))
        val rxCounter = RegInit(0.U(log2Ceil(bw).W))
        val rxCounter_pipe_0 = RegNext(rxCounter)
        val rxCounter_pipe_1 = RegNext(rxCounter_pipe_0)
        val data_pipe_0 = RegNext(io.rxSbIo.data)
        val data_pipe_1 = RegNext(data_pipe_0)
        val clock_pipe_0 = RegNext(io.rxSbIo.clk)
        val clock_pipe_1 = RegNext(clock_pipe_0)
        val enable = WireInit(false.B)
        val enable_counter = RegInit(0.U(2.W))
        enable := clock_pipe_0.asBool ^ clock_pipe_1.asBool
        when(enable){
            enable_counter := 2.U
        }.elsewhen(enable_counter > 0.U){
            enable_counter := enable_counter - 1.U
        }

        fifo.io.enq.valid := false.B 
        fifo.io.enq.bits := 0.U
        fifo.io.enq_clock := io.clk_800
        fifo.io.enq_reset := reset
        when(enable_counter.orR){
            when(rxCounter_pipe_1 === (bw-1).U){
                shiftReg := 0.U | (data_pipe_1 << (bw-1).U)
                fifo.io.enq.valid := true.B
                fifo.io.enq.bits := shiftReg 
                
            }.otherwise{
                shiftReg := (shiftReg >> 1.U) | (data_pipe_1 << (bw-1).U)
            }
            rxCounter := rxCounter + 1.U
        }
    }
}

class TxSideband(depth:Int, width: Int = 1, afeParams: AfeParams=AfeParams()) extends Module {
    val io = IO(new Bundle {
        // sbAfeIo bw 64 bit
        // val sbAfeIo = new SidebandAfeIo(AfeParams())
        val rxSbAfe = Flipped(Decoupled(Bits(afeParams.sbSerializerRatio.W)))
        val clk_800 = Input(Clock())
        val txSbIo = Output(new SidebandIo())
    })
    private val bw = afeParams.sbSerializerRatio
    val fifo = Module(new AsyncFifoStefan(depth, bw))

    io.txSbIo.clk := io.clk_800

    fifo.io.enq.valid := io.rxSbAfe.valid
    fifo.io.enq.bits := io.rxSbAfe.bits
    io.rxSbAfe.ready := fifo.io.enq.ready
    fifo.io.enq_clock := clock
    fifo.io.enq_reset := reset
    
    withClock(io.clk_800){
        val txCounter = RegInit(0.U(log2Ceil(64).W))
        val txIng = RegInit(false.B) // 0 is idle for 32 UI, 1 is transmitting 64 UI
        val shiftReg = RegInit(0.U(bw.W))

        fifo.io.deq.ready := false.B // always set to true for io.deq
        fifo.io.deq_clock := io.clk_800 
        fifo.io.deq_reset := reset 
        // when(fifo.io.deq.ready){
        when(txCounter === 31.U && txIng === false.B){
            shiftReg := fifo.io.deq.bits
            fifo.io.deq.ready := true.B 
            txIng := true.B
            txCounter := 0.U
        }.elsewhen(txCounter === 63.U && txIng === true.B){
            txIng := false.B
            txCounter := 0.U
        }.otherwise{
            txCounter := txCounter + 1.U
        }
        when(txIng === true.B){
            shiftReg := shiftReg >> 1.U
        }
        // }
        // shiftReg := Mux(txIng, shiftReg >> 1.U, shiftReg)
        // io.txSbIo.data := Mux(txIng, shiftReg(0), 0.U)
        io.txSbIo.data := shiftReg(0)
        io.txSbIo.clk := Mux(txIng, io.clk_800.asBool, false.B).asClock
    }
}

// This module accepts data from analog and send to adapter
class RxMainband(depth: Int, width: Int, version: Int, lanes: Int = 16, BYTE: Int = 8) extends Module {
    val io = IO(new Bundle {
        // should use rx of mbafeIo
        val mbAfeIo = new MainbandAfeIo(AfeParams())
        val rxMbIo = Input(new MainbandIo())

        // Dummy signals for testing
        val startEnq = Input(Bool())
        val clkn_out = Output(Clock())
        
    })
    val queueParams = AsyncQueueParams(
        depth = depth,   // Custom depth
        sync = 3,     // Custom synchronization stages
        safe = true,  // Use safe reset
        narrow = false // Use wide configuration
    ) 
    // Since sending data to adapter,
    // This module Should drive mbAfeIo tx data
    io.mbAfeIo.rxData.ready := false.B 
    io.clkn_out := io.rxMbIo.clkn
    // io.mbAfeIo.rxData.bits := Seq.fill(lanes)(0.U) 
    val txMbAfeData = io.mbAfeIo.txData

    // This module receives data from analog, and store into async buffer
    // val rxMbFifos = Seq.fill(lanes)(Module (new AsyncQueue(Bits(BYTE.W), queueParams)))
    val rxMbFifos = Seq.fill(lanes)(Module (new AsyncFifoStefan(depth, BYTE)))

        // Enqueue end from analog
    withClock(io.rxMbIo.clkp) {
        val mbIoValid_pipe_0 = RegNext(io.rxMbIo.valid)
        val mbIoValid_pipe_1 = RegNext(mbIoValid_pipe_0)
        val mbIoValid_pipe_2 = RegNext(mbIoValid_pipe_1)
        val mbIoValid_next = RegNext(mbIoValid_pipe_2)

        // Shiftregs to deserialize and store into the async buffer
        val rxMbShiftRegs = Seq.fill(lanes)(RegInit(0.U(BYTE.W)))
        val rxMbShiftRegs_next = Seq.fill(lanes)(RegInit(0.U(BYTE.W)))
        val rxMbShiftRegs_xor = Seq.fill(lanes)(WireInit(0.U(BYTE.W)))

        val rxMbUICounter = RegInit(0.U(log2Ceil(BYTE).W))
        
        val rxMbUICounter_next = RegNext(rxMbUICounter)

        // val rxMbIoData_next = RegNext(io.rxMbIo.data)
        val rxMbIoData_next = RegInit(0.U(width.W))
        val fifo_enq_valid_next = RegNext(rxMbUICounter_next === 7.U && rxMbUICounter === 0.U)
        val internal_valid = (mbIoValid_next ^ io.rxMbIo.valid) | (mbIoValid_next & io.rxMbIo.valid)
         
        rxMbFifos.zipWithIndex.foreach{ case(rxMbFifo, i) =>

            rxMbFifo.io.enq_clock := io.rxMbIo.clkp 
            rxMbFifo.io.enq_reset := reset
            rxMbFifo.io.enq.valid := false.B
            // For clear testing visuals, should always connect to signal path for minimal delay
            rxMbFifo.io.enq.bits := 0.U
            // rxMbFifo.io.enq.bits := Cat(rxMbShiftRegs.reverse)
            // There's a little overlap of assert high of io.rxMbIo.valid and last stage pipeline
            // 
            when(internal_valid){
                rxMbIoData_next := io.rxMbIo.data 
                when(rxMbUICounter === 0.U) {
                    for(i <- 0 until lanes) {
                        rxMbShiftRegs(i) := 0.U | io.rxMbIo.data (i)
                    }
                    rxMbShiftRegs_next(i) := rxMbShiftRegs(i)

                }.otherwise {
                    for(i <- 0 until lanes) {
                        rxMbShiftRegs(i) := rxMbShiftRegs(i) << 1.U | io.rxMbIo.data (i)
                    } 
                    rxMbShiftRegs_next(i) := 0.U

                }
                rxMbUICounter := rxMbUICounter + 1.U
            }
            rxMbShiftRegs_xor(i) := rxMbShiftRegs(i) ^ rxMbShiftRegs_next(i)
            when((rxMbUICounter_next === 7.U && rxMbUICounter === 0.U) 
                ^ fifo_enq_valid_next 
                ) {
                rxMbFifo.io.enq.valid := true.B
                rxMbFifo.io.enq.bits := Cat(rxMbShiftRegs_xor.reverse)
            }
        }
    }
    withClock(clock) {
        
        rxMbFifos.zipWithIndex.foreach{ case(rxMbFifo, i) =>

                // Dequeue end to drive 
            rxMbFifo.io.deq_clock := clock
            rxMbFifo.io.deq_reset := reset
            txMbAfeData.bits(i) := rxMbFifo.io.deq.bits
            txMbAfeData.valid := rxMbFifo.io.deq.valid 
            rxMbFifo.io.deq.ready := txMbAfeData.ready
        }
    }
}

class PhyTest extends Module {
    val io = IO(new Bundle {
        val tx_user = new MainbandAfeIo(AfeParams())
        val rx_user = new MainbandAfeIo(AfeParams())
        val clkp = Input(Clock())
        val clkn = Input(Clock())
        val startDeq = Input(Bool())
        val startEnq = Input(Bool())
        val clkn_out = Output(Clock())
    })


    val sender = Module(new TxMainband(16, 16, 0))
    val receiver = Module(new RxMainband(16, 16, 0))
    sender.io.mbAfeIo <> io.tx_user 
    sender.io.txMbIo  <> receiver.io.rxMbIo 
    sender.io.clkp := io.clkp 
    sender.io.clkn := io.clkn 
    sender.io.track := 0.U 
    sender.io.startDeq := io.startDeq 

    receiver.io.mbAfeIo <> io.rx_user 
    receiver.io.startEnq := io.startEnq
    io.clkn_out := receiver.io.clkn_out
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
        txMbAfeData.bits := rxMbFifo.io.deq.bits
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

// To execute do:
// runMain edu.berkeley.cs.ucie.digital.afe.TxMainbandVerilog 
object RxMainbandVerilog extends App {
    (new ChiselStage).emitSystemVerilog(new RxMainband(16, 16, 0)
    )
}

object PhyTestVerilog extends App {
    (new ChiselStage).emitSystemVerilog(new PhyTest())
}

object TxSidebandVerilog extends App {
    (new ChiselStage).emitSystemVerilog(new TxSideband(16))
}


object RxSidebandVerilog extends App {
    (new ChiselStage).emitSystemVerilog(new RxSideband(16))
}