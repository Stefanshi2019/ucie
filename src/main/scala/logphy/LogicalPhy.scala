package edu.berkeley.cs.ucie.digital
package logphy


import chisel3._
import chisel3.util._
import interfaces._
import sideband._

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
    
    // sidebandMessageEncoder = new SidebandMessageEncoder()

    // def SendSidebandMessage(): Unit{
    //     sbAfe.txData.valid := true.B
    //     sbAfe.txData.irdy := !sbAfe.txData.ready
    //     sbAfe.txData.bits := 5.U
    // }
}

class SidebandMessageDecoder extends Module {
    val io = IO(new Bundle {
        val fire = Input(Bool())
        val msgIn = Input(Bits(32.W))
        val msgHeaderOut = Output(new SidebandMessageHeader())
        val data = Output(Bits(32.W))
        val phase = Output(Bits(4.W))
    })
    val phaseCounter = RegInit(0.U(32.W))
    io.msgHeaderOut.opcode := io.msgIn(4, 0)
    // if msb of opcode is 1, it's register access type; otherwise it's message type
    // when(io.msgHeaderOut.opcode(4) === 0.U){
    //     // Register type to be implemented
        
    // }.otherwise{
        // phase 0
        io.msgHeaderOut.srcid := io.msgIn(31, 29)
        io.msgHeaderOut.rsvd_00 := io.msgIn(28, 27)
        io.msgHeaderOut.rsvd_01 := io.msgIn(26, 22)
        io.msgHeaderOut.msgCode := io.msgIn(21, 14)
        io.msgHeaderOut.rsvd_02 := io.msgIn(13, 5)

        // phase 1
        io.msgHeaderOut.dp := io.msgIn(31)
        io.msgHeaderOut.cp := io.msgIn(30)
        io.msgHeaderOut.rsvd_10 := io.msgIn(29, 27)
        io.msgHeaderOut.dstid := io.msgIn(26, 24)
        io.msgHeaderOut.msgInfo := io.msgIn(23, 8)
        io.msgHeaderOut.msgSubCode := io.msgIn(7, 0)
    // }
    when(io.fire) {
        phaseCounter := phaseCounter + 1.U
    }.otherwise{
        phaseCounter := 0.U
    }
}












class SidebandMessageEncoder extends Module {
    val io = IO(new Bundle{
        val enable = Input(Bool())
        val ready = Input(Bool())
        val msgHeaderIn = Input(new SidebandMessageHeader())
        val data0 = Input(Bits(32.W))
        val data1 = Input(Bits(32.W))
        val msgOut = Output(Bits(32.W))
        val done = Output(Bool())
    })

    val phase0 = Cat(
                    io.msgHeaderIn.srcid.asUInt, 
                    io.msgHeaderIn.rsvd_00,
                    io.msgHeaderIn.rsvd_01,
                    io.msgHeaderIn.msgCode.asUInt,
                    io.msgHeaderIn.rsvd_02,
                    io.msgHeaderIn.opcode.asUInt
                    )
    val phase1 = Cat(
                    io.msgHeaderIn.dp,
                    io.msgHeaderIn.cp,
                    io.msgHeaderIn.rsvd_10,
                    io.msgHeaderIn.dstid.asUInt,
                    io.msgHeaderIn.msgInfo.asUInt,
                    io.msgHeaderIn.msgSubCode.asUInt
                    )
    // Take care of data later
    // assert (phase0.getWidth == 32.U, "Width not equal to 32")
    println(s"Width of phase0: ${phase0.getWidth}")
    println(s"Width of phase1: ${phase1.getWidth}")
    val counter = RegInit(0.U(4.W))
    val msgOutReg = RegInit(0.U(32.W))
    val doneReg = RegInit(false.B)
    // When asked to send, and other side ready, start 

    io.done := doneReg
    io.msgOut := msgOutReg

    when(io.enable && io.ready){
        // send message
        when(counter === 0.U){
            msgOutReg := phase0
            counter := counter + 1.U
        }.elsewhen(counter === 1.U){
            msgOutReg := phase1
            counter := counter + 1.U
        }.elsewhen(counter === 2.U){
            when(io.msgHeaderIn.opcode === PacketType.MessageWith64bData.asUInt){
                counter := counter + 1.U
            }.otherwise{
                doneReg := true.B
            }
            msgOutReg := io.data0
        }.elsewhen(counter === 3.U){
            msgOutReg := io.data1
            counter := counter + 1.U
        }.otherwise{
            doneReg := true.B
        }
        // when completed and enable deasserted, reset counter, deassert doneReg 
    }.elsewhen(doneReg && !io.enable){
        doneReg := false.B
        counter := 0.U
    }
    
}