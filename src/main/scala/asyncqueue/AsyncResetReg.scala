// See LICENSE.SiFive for license details.
package edu.berkeley.cs.ucie.digital
package freechips.asyncqueue

// import Chisel._
// import chisel3.util.HasBlackBoxResource
import chisel3._
import chisel3.util._
/** This black-boxes an Async Reset
  *  (or Set)
  * Register.
  *  
  * Because Chisel doesn't support
  * parameterized black boxes, 
  * we unfortunately have to 
  * instantiate a number of these.
  *  
  *  We also have to hard-code the set/
  *  reset behavior.
  *  
  *  Do not confuse an asynchronous
  *  reset signal with an asynchronously
  *  reset reg. You should still 
  *  properly synchronize your reset 
  *  deassertion.
  *  
  *  @param d Data input
  *  @param q Data Output
  *  @param clk Clock Input
  *  @param rst Reset Input
  *  @param en Write Enable Input
  *  
  */

class AsyncResetReg(resetValue: Int = 0)
  extends BlackBox with HasBlackBoxResource
{
  val io = IO (new Bundle {
    val d = Input(Bool())
    val q = Output(Bool())
    val en = Input(Bool())

    val clk = Input(Clock())
    val rst = Input(Bool())
  })

  addResource("/vsrc/AsyncResetReg.v")
}

class SimpleRegIO(val w: Int) extends Bundle{
  // val d = UInt(INPUT, width = w)
  val d = Input(UInt(w.W))
  // val q = UInt(OUTPUT, width = w)
  val q = Output(UInt(w.W))

  val en = Input(Bool())
}

class AsyncResetRegVec(val w: Int, val init: BigInt) extends Module {
  val io = IO(new SimpleRegIO(w))

  val async_regs = List.tabulate(w) { idx =>
    val on = if (init.testBit(idx)) 1 else 0
    Module(new AsyncResetReg(on))
  }

  val q = for ((reg, idx) <- async_regs.zipWithIndex) yield {
    reg.io.clk := clock
    reg.io.rst := reset
    reg.io.d   := io.d(idx)
    reg.io.en  := io.en
    reg.suggestName(s"reg_$idx")
    reg.io.q
  }

  io.q := Cat(q)

  override def desiredName = s"AsyncResetRegVec_w${w}_i${init}"

}

object AsyncResetReg {
  // Create Single Registers
  def apply(d: Bool, clk: Clock, rst: Bool, init: Boolean, name: Option[String]): Bool = {
    val reg = Module(new AsyncResetReg(if (init) 1 else 0))
    reg.io.d := d
    reg.io.clk := clk
    reg.io.rst := rst
    // reg.io.en  := Bool(true)
    reg.io.en := true.B
    name.foreach(reg.suggestName(_))
    reg.io.q
  }

  def apply(d: Bool, clk: Clock, rst: Bool): Bool = apply(d, clk, rst, false, None)
  def apply(d: Bool, clk: Clock, rst: Bool, name: String): Bool = apply(d, clk, rst, false, Some(name))

  // Create Vectors of Registers
  def apply(updateData: UInt, resetData: BigInt, enable: Bool, name: Option[String] = None): UInt = {
    val w = updateData.getWidth max resetData.bitLength
    val reg = Module(new AsyncResetRegVec(w, resetData))
    name.foreach(reg.suggestName(_))
    reg.io.d := updateData
    reg.io.en := enable
    reg.io.q
  }
  def apply(updateData: UInt, resetData: BigInt, enable: Bool, name: String): UInt = apply(updateData,
    resetData, enable, Some(name))


  def apply(updateData: UInt, resetData: BigInt): UInt = apply(updateData, resetData, enable=true.B)
  def apply(updateData: UInt, resetData: BigInt, name: String): UInt = apply(updateData, resetData, enable=true.B, Some(name))

  def apply(updateData: UInt, enable: Bool): UInt = apply(updateData, resetData=BigInt(0), enable)
  def apply(updateData: UInt, enable: Bool, name: String): UInt = apply(updateData, resetData=BigInt(0), enable, Some(name))

  def apply(updateData: UInt): UInt = apply(updateData, resetData=BigInt(0), enable=true.B)
  def apply(updateData: UInt, name:String): UInt = apply(updateData, resetData=BigInt(0), enable=true.B, Some(name))
}

