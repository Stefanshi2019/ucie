// See LICENSE.SiFive for license details.
package edu.berkeley.cs.ucie.digital
package freechips.asyncqueue

// import Chisel._
// import chisel3.util.{DecoupledIO, Decoupled, Irrevocable, IrrevocableIO, ReadyValidIO}
import chisel3.util._
import chisel3._

class CrossingIO[T <: Data](gen: T) extends Bundle {
  // Enqueue clock domain
  // val enq_clock = Clock(INPUT)
  val enq_clock = Input(Clock())
  // val enq_reset = Bool(INPUT) // synchronously deasserted wrt. enq_clock
  val enq_reset = Input(Bool())
  // val enq = Decoupled(gen).flip
  val enq = Flipped(Decoupled(gen))
  // Dequeue clock domain
  // val deq_clock = Clock(INPUT)
  val deq_clock = Input(Clock())
  // val deq_reset = Bool(INPUT) // synchronously deasserted wrt. deq_clock
  val deq_reset = Input(Bool())
  val deq = Decoupled(gen)
}

abstract class Crossing[T <: Data] extends Module {
  val io: CrossingIO[T]
}
