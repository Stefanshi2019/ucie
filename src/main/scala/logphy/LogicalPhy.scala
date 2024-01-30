package edu.berkeley.cs.ucie.digital
package logphy


import chisel3._
import chisel3.util._
import interfaces._


class LogicalPhy(
    afeParams: AfeParams,
    rdiParams: RdiParams,
) extends Module {
  val io = IO(new Bundle {
    val rdi = Flipped(new Rdi(rdiParams))
    val mbAfe = new MainbandAfeIo(afeParams)
    val sbAfe = new SidebandAfeIo(afeParams)
  })

    val waitForAck = RegInit(false.B)
    val ackReceived = RegInit(false.B)

    // FSM Begin ---------------------------------------------------------------
    // protocal, adaptor, and logic phy should all have their own FSM of same states and transitions
    // Refer to p250 of UCIe spec
    // Registers
    val currentState = RegInit(PhyState.reset)
    val nextState   = Wire(PhyState.reset)
    // Decode signalS
    val stateReq = RegInit(PhyState.reset) // will be done later

    switch(currentState) {
        // Link Error, Disabled, Link Reset implemented below switch
        // These transitions don't require handshake
        // ?? Can go through active, but not necessarily?
        is(PhyState.reset) {
            when(io.rdi.lpStateReq === PhyStateReq.active && !waitForAck) {
                Transition_ResetToActive()
            }.elsewhen(waitForAck && ackReceived) {
                waitForAck := false.B
                ackReceived := false.B
                nextState := PhyState.active
            }
        }
        is(PhyState.active){
            nextState := PriorityMux(Seq(
                // Direct transition, no handshake required
                // transition to reset requires NOP
                (io.rdi.lpStateReq === PhyStateReq.nop, PhyState.reset),
                (io.rdi.lpStateReq === PhyStateReq.l1, PhyState.l1),
                (io.rdi.lpStateReq === PhyStateReq.l2, PhyState.l2),
                (io.rdi.lpStateReq === PhyStateReq.retrain, PhyState.retrain)
            ))
        }
        // DAPM unimplemented, same as L1 transition
        // l1 unsure, l1 for power management
        is(PhyState.l1) {
            when(io.rdi.lpStateReq === PhyStateReq.retrain) {
                nextState := PhyState.retrain
            }
        }
        is(PhyState.linkReset) {
            nextState := PriorityMux(Seq(
                // transition to reset requires NOP
                (io.rdi.lpStateReq === PhyStateReq.nop, PhyState.reset),
                (io.rdi.lpStateReq === PhyStateReq.active, PhyState.active),
                (io.rdi.lpStateReq === PhyStateReq.l1, PhyState.l1),
                (io.rdi.lpStateReq === PhyStateReq.l2, PhyState.l2)
            )) 
        }
        
        is(PhyState.retrain) {
            // Adapter requests Retrain on io.rdi if any of the following events occur:
            // 1. Software writes to the Retrain bit and the Link is in Active state
            // 2. Number of CRC or parity errors detected crosses a threshold. The specific algorithm
            // for determining this is implementation specific.
            // 3. Protocol Layer requests Retrain (only applicable for Raw Mode)
            // 4. any other implementation specific condition (if applicable)
            // Physical Layer triggers a Retrain transition on io.rdi if:
            // 1. Valid framing errors are observed.
            // 2. Remote Physical Layer requests Retrain entry.
            // 3. Adapter requests Retrain
            when(io.rdi.lpStateReq === PhyStateReq.active) {
                nextState := PhyState.active
            }
        }
        is(PhyState.disabled) {
            nextState := PriorityMux(Seq(
                // transition to reset requires NOP
                (io.rdi.lpStateReq === PhyStateReq.nop, PhyState.reset),
                (io.rdi.lpStateReq === PhyStateReq.active, PhyState.active),
                (io.rdi.lpStateReq === PhyStateReq.l1, PhyState.l1),
                (io.rdi.lpStateReq === PhyStateReq.l2, PhyState.l2),
                (io.rdi.lpStateReq === PhyStateReq.retrain, PhyState.retrain)

            ))  
        }
        // l2 unsure, l2 for power management
        is(PhyState.l2) {
                nextState := PhyState.reset
        }
        is(PhyState.linkError) {
            nextState := PriorityMux(Seq(
                (io.rdi.lpStateReq === PhyStateReq.nop, PhyState.reset),
                (io.rdi.lpStateReq === PhyStateReq.active, PhyState.active),
                (io.rdi.lpStateReq === PhyStateReq.l1, PhyState.l1),
                (io.rdi.lpStateReq === PhyStateReq.l2, PhyState.l2),
                (io.rdi.lpStateReq === PhyStateReq.retrain, PhyState.retrain)
            ))
        }
    }
    // Following 3 states could be transitioned from any other states
    // Disabled and LinkError transition occurs also upon remote side band message request
    // Implement later
    currentState := PriorityMux(Seq(
        (io.rdi.lpLinkError, PhyState.linkError),
        (io.rdi.lpStateReq === PhyStateReq.nop, PhyState.reset),
        (io.rdi.lpStateReq === PhyStateReq.linkReset, PhyState.linkReset),
        (io.rdi.lpStateReq === PhyStateReq.disabled, PhyState.disabled),
        (true.B, nextState)
    ))
    // FSM End ---------------------------------------------------------------

    // Sub-Actions Begin ------------------------------------------------------------------
    // For actions, refer to p250
    def Transition_ResetToActive (): Unit = {
        // Can transition to active, l1, and l2
        // Only side band message (dest state) differs

        // 1. drive side band, send message to request active
        // 2. decode side band message, check if ack from partner, if so, turn on ack
    }
    // Sub-Actions End ------------------------------------------------------------------


    // Datapath Begin ------------------------------------------------------------------
    when(currentState === PhyState.reset) {

    }
}
