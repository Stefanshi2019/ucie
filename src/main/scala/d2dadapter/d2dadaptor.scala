package edu.berkeley.cs.ucie.digital
package d2dadaptor

import chisel3._
import chisel3.util._
import interfaces._

// The FSM of RDI and FDI are identical,
// each is embedded into LogicalPhy and Protocal respectively
class d2dadaptor (
    afeParams: AfeParams,
    rdiParams: RdiParams,
    fdiParams: FdiParams
) extends Module {
    // Not sure about AfeParams, probably only for logicalPhy?
    val io = IO(new Bundle {
        val fdi = Flipped(new Fdi(fdiParams))
        val rdi = new Rdi(rdiParams)
    })
    


}