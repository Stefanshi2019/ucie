package edu.berkeley.cs.ucie.digital
package interfaces

import chisel3._
import chisel3.util._

class d2dadaptor (
    afeParams: AfeParams,
    rdiParams: RdiParams,
    fdiParams: FdiParams
) extends Module {
    val io = IO(new Bundle {
        val rdi = new Rdi(rdiParams)
        val fdi = new Fdi(fdiParams)
        
    })
}
