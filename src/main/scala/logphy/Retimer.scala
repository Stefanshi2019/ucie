package edu.berkeley.cs.ucie.digital
package logphy


import chisel3._
import chisel3.util._
import interfaces._
import sideband._

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