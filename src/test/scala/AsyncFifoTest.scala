package edu.berkeley.cs.ucie.digital
package freechips.asyncqueue

import chisel3.stage.ChiselStage
import chisel3._
import chiseltest._
import org.scalatest.funspec.AnyFunSpec
import java.nio.channels.AsynchronousByteChannel
// import interfaces._
// import sideband._
// import freechips.asyncqueue._

class AsyncFifoTestDut[T <: Data](gen: T, params: AsyncQueueParams = AsyncQueueParams()) extends Module{
    val io = IO(new CrossingIO(gen))
    // enq_clock
    // enq_reset
    // enq (flipped)
    // deq_clock
    // deq_reset
    // deq (Decouple)
    val asyncfifo = Module(new AsyncQueue(gen, params))
    asyncfifo.io.enq_clock := clock // Not sure about clock
    asyncfifo.io.enq_reset := io.enq_reset

    asyncfifo.io.enq.bits  := io.enq.bits
    asyncfifo.io.enq.valid := io.enq.valid
    io.enq.ready           := asyncfifo.io.enq.ready

    asyncfifo.io.deq_clock := clock
    asyncfifo.io.deq_reset := io.deq_reset

    io.deq.bits := asyncfifo.io.deq.bits
    io.deq.valid := asyncfifo.io.deq.valid
    asyncfifo.io.deq.ready := io.deq.ready
}

// class AsyncFifoTest extends AnyFunSpec with ChiselScalatestTester {
//   describe("DUT Module with Async source/sink") {
//      it("should be able to dequeue after all enqueue is done") {
//       test(
//         new AsyncFifoTestDut(
//           Bits(1.W), // Not sure about what "T" is
//           AsyncQueueParams() // Not sure about "sync"
//         )
//       ).withAnnotations(Seq(WriteVcdAnnotation)) { c =>
//         // enq_clock
//         // enq_reset
//         // enq (flipped)
//         // deq_clock
//         // deq_reset
//         // deq (Decouple)

//         // Set enq & deq reset high for 5 cycles, enq valid=0, deq ready=0
//         c.io.enq_reset.poke(true.B)
//         c.io.deq_reset.poke(true.B)
//         c.io.enq.valid.poke(false.B)
//         c.io.deq.ready.poke(false.B)
//         c.clock.step(5)
//         c.io.enq_reset.poke(false.B)
//         c.io.deq_reset.poke(false.B)
//         c.clock.step(2)

//         // If enq ready, send bit 0 to it
//         // NOTE: do while is not supported by scala so using if here. mainband don't have ready so doesn't matter anyways
//         if (c.io.enq.ready.peek().litToBoolean) {
//             c.io.enq.bits.poke(0.U)
//             c.io.enq.valid.poke(true.B)
//             c.clock.step()
//         }
//         // If enq ready, send bit 1 to it
//         if (c.io.enq.ready.peek().litToBoolean) {
//             c.io.enq.bits.poke(1.U)
//             c.io.enq.valid.poke(true.B)
//             c.clock.step()
//         }
//         // If enq ready, send bit 0 to it
//         if (c.io.enq.ready.peek().litToBoolean) {
//             c.io.enq.bits.poke(0.U)
//             c.io.enq.valid.poke(true.B)
//             c.clock.step()
//         }
//         // If enq ready, send bit 1 to it
//         if (c.io.enq.ready.peek().litToBoolean) {
//             c.io.enq.bits.poke(1.U)
//             c.io.enq.valid.poke(true.B)
//             c.clock.step()
//         }
//         // No more inputs
//         c.io.enq.valid.poke(false.B)
        
//         // deq ready to receive
//         c.io.deq.ready.poke(true.B)

//         while (c.io.deq.valid.peek().litToBoolean) {
//             println(s"Current dequeue out: ${c.io.deq.bits.peek().litValue}")
//             c.clock.step()
//         }

//         c.io.deq.ready.poke(false.B)
//       }
//     }
//   }
// }