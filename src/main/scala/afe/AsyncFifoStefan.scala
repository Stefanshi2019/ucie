package edu.berkeley.cs.ucie.digital
package afe

import chisel3._
import chisel3.util._

class AsyncFifoStefan (depth: Int, width: Int) extends BlackBox(
    Map("DEPTH" -> depth, "WIDTH" -> width)
) with HasBlackBoxResource {
    val io = IO(new Bundle {
        val rst = Input(Bool())
        val clk_w = Input(Bool())
        val valid_w = Input(Bool())
        val ready_w = Output(Bool())
        val data_w = Input(Bits(width.W))
        val clk_r = Input(Bool())
        val valid_r = Output(Bool())
        val ready_r = Input(Bool())
        val data_r = Output(Bits(width.W))
    })
    addResource("/vsrc/AsyncFifoStefan.sv")
}