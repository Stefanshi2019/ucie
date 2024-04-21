// package edu.berkeley.cs.ucie.digital
// package logphy


// import chisel3._
// import chisel3.util._
// import interfaces._
// import sideband._


// class SidebandWrapper extends Module{
//     val io = IO(new Bundle {
//         val sendEnable = Input(Bool())
//         val sbTx = Decoupled(Bits(32.W))
//         val sbRx = Flipped(Decoupled(Bits(32.W)))
//         // To be added
//     })
//     // Tx part begin ---------------------------------------
//     // Need FSM to send stateChange.Active upon io.sendEnable, typically Tx
//     // Should wait for the Ack message from other side, typically Rx
//     // Upon receiving Ack, should output success
//     val waitForAck = RegInit(false.B)
//     val ackReceived = RegInit(false.B)
//     val sbMsgEnable = WireInit(false.B) 
//     val sbMsgReady = WireInit(false.B) 
//     val sbMsgData0 = WireInit(0.U(32.W))
//     val sbMsgData1 = WireInit(0.U(32.W))
  
//     val sbOpcode = WireInit(Opcode.MessageWithoutData)
//     val sbMsgCode = WireInit(MsgCode.Nop)
//     val sbMsgInfo = WireInit(MsgInfo.RegularResponse) //RegularResponse = DontCare
//     val sbMsgSubCode = WireInit(MsgSubCode.Crd) // Crd == Nop

//     val sbSender = new SidebandMessageSender()
//     val sbEncoder = new SidebandMessageEncoder()
    
//     sbSender.io.opcode := sbOpcode
//     sbSender.io.msgCode := sbOpcode
//     sbSender.io.msgInfo := sbMsgInfo
//     sbSender.io.msgSubCode := sbMsgSubCode
    
//     sbEncoder.io.enable := sbMsgEnable
//     sbEncoder.io.ready := sbMsgReady
//     sbEncoder.io.msgHeaderIn <> sbSender.io.msgHeaderOut
//     sbEncoder.io.data0 := sbMsgData0
//     sbEncoder.io.data1 := sbMsgData1

//     io.sbTx.valid := sbMsgEnable & sbEncoder.io.done
//     sbEncoder.io.ready := io.sbRx.ready
//     io.sbTx.bits := sbEncoder.io.msgOut
//     // To be done, add any code as you wish
//     // Tx part End ---------------------------------------
    
//     // Rx part Begin ---------------------------------------
//     // Need FSM to listen and receive stateChange.Active
//     // Should encode and send an Ack message
//     val msgHeaderOut = WireInit(new SidebandMessageHeader())
//     val data = WireInit(0.U(32.W))
//     val phase = WireInit(0.U(4.W))

//     val sbMsgDecoder = new SidebandMessageDecoder()

//     sbMsgDecoder.io.fire := io.sbRx.valid & io.sbTx.ready
//     sbMsgDecoder.io.msgIn := io.sbRx.bits
    
//     msgHeaderOut := sbMsgDecoder.io.msgHeaderOut
//     data := sbMsgDecoder.io.data
//     phase := sbMsgDecoder.io.phase 
//     // To be done, add any code as you wish
//     // Rx part End ---------------------------------------
// }

// class LogicalPhy(
//     afeParams: AfeParams,
//     rdiParams: RdiParams,
// ) extends Module {
//   val io = IO(new Bundle {
//     val rdi = Flipped(new Rdi(rdiParams))
//     val mbAfe = new MainbandAfeIo(afeParams)
//     val sbAfe = new SidebandAfeIo(afeParams)

//   })

//     // Signal Definition Begin ------------------------------------------------------------------
//     val waitForAck = RegInit(false.B)
//     val ackReceived = RegInit(false.B)
//     val sbMsgEnable = WireInit(false.B) 
//     val sbMsgReady = WireInit(false.B) //// Should be from other side, for now just simulate
//     val sbMsgData0 = WireInit(0.U(32.W))
//     val sbMsgData1 = WireInit(0.U(32.W))
  
//     val sbOpcode = WireInit(Opcode.MessageWithoutData)
//     val sbMsgCode = WireInit(MsgCode.Nop)
//     val sbMsgInfo = WireInit(MsgInfo.RegularResponse) //RegularResponse = DontCare
//     val sbMsgSubCode = WireInit(MsgSubCode.Crd) // Crd == Nop
//     // Signal Definition End ------------------------------------------------------------------

//     // Submodules Begin ------------------------------------------------------------------
//     val sbSender = new SidebandMessageSender()
//     val sbEncoder = new SidebandMessageEncoder()
    
//     // Submodules End ------------------------------------------------------------------

//     // Connections Begin ------------------------------------------------------------------
//     sbSender.io.opcode := sbOpcode
//     sbSender.io.msgCode := sbOpcode
//     sbSender.io.msgInfo := sbMsgInfo
//     sbSender.io.msgSubCode := sbMsgSubCode
    
//     sbEncoder.io.enable := sbMsgEnable
//     sbEncoder.io.ready := sbMsgReady
//     sbEncoder.io.msgHeaderIn <> sbSender.io.msgHeaderOut
//     sbEncoder.io.data0 := sbMsgData0
//     sbEncoder.io.data1 := sbMsgData1
    
//     // Connections End ------------------------------------------------------------------

//     // FSM Begin ------------------------------------------------------------------
//     // protocal, adaptor, and logic phy should all have their own FSM of same states and transitions
//     // Refer to p250 of UCIe spec
//     // Registers
//     val currentState = RegInit(PhyState.reset)
//     val nextState   = Wire(PhyState.reset)
//     // Decode signalS
//     val stateReq = RegInit(PhyState.reset) // will be done later

//     switch(currentState) {
//         // Link Error, Disabled, Link Reset implemented below switch
//         // These transitions don't require handshake
//         // ?? Can go through active, but not necessarily?
//         is(PhyState.reset) {
//             when(io.rdi.lpStateReq === PhyStateReq.active && !waitForAck) {
//                 Transition_ResetToActive()
//             }.elsewhen(waitForAck && ackReceived) {
//                 waitForAck := false.B
//                 ackReceived := false.B
//                 nextState := PhyState.active
//             }
//         }
//         is(PhyState.active){
//             nextState := PriorityMux(Seq(
//                 // Direct transition, no handshake required
//                 // transition to reset requires NOP
//                 (io.rdi.lpStateReq === PhyStateReq.nop, PhyState.reset),
//                 (io.rdi.lpStateReq === PhyStateReq.l1, PhyState.l1),
//                 (io.rdi.lpStateReq === PhyStateReq.l2, PhyState.l2),
//                 //(Not applicable for CXL Flit Mode with Retry in the Adapter)
//                 (io.rdi.lpStateReq === PhyStateReq.retrain, PhyState.retrain)
//             ))
//         }
//         // DAPM unimplemented, same as L1 transition
//         // l1 unsure, l1 for power management
//         is(PhyState.l1) {
//             when(io.rdi.lpStateReq === PhyStateReq.retrain) {
//                 nextState := PhyState.retrain
//             }
//         }
//         is(PhyState.linkReset) {
//             nextState := PriorityMux(Seq(
//                 // transition to reset requires NOP
//                 (io.rdi.lpStateReq === PhyStateReq.nop, PhyState.reset),
//                 (io.rdi.lpStateReq === PhyStateReq.active, PhyState.active),
//                 (io.rdi.lpStateReq === PhyStateReq.l1, PhyState.l1),
//                 (io.rdi.lpStateReq === PhyStateReq.l2, PhyState.l2)
//             )) 
//         }
        
//         is(PhyState.retrain) {
//             // Adapter requests Retrain on io.rdi if any of the following events occur:
//             // 1. Software writes to the Retrain bit and the Link is in Active state
//             // 2. Number of CRC or parity errors detected crosses a threshold. The specific algorithm
//             // for determining this is implementation specific.
//             // 3. Protocol Layer requests Retrain (only applicable for Raw Mode)
//             // 4. any other implementation specific condition (if applicable)
//             // Physical Layer triggers a Retrain transition on io.rdi if:
//             // 1. Valid framing errors are observed.
//             // 2. Remote Physical Layer requests Retrain entry.
//             // 3. Adapter requests Retrain

//             // TODO: inputs to be considered: 1. SW retrain bit 2. number of CRC errors 3. protocol layer request, raw only
//             // TODO: upon entering retrain, events:
//             // 1. Propagate retrain to all adapter LSMs 
//             when(io.rdi.lpStateReq === PhyStateReq.active) {
//                 nextState := PhyState.active
//             }
//         }
//         is(PhyState.disabled) {
//             nextState := PriorityMux(Seq(
//                 // transition to reset requires NOP
//                 (io.rdi.lpStateReq === PhyStateReq.nop, PhyState.reset),
//                 (io.rdi.lpStateReq === PhyStateReq.active, PhyState.active),
//                 (io.rdi.lpStateReq === PhyStateReq.l1, PhyState.l1),
//                 (io.rdi.lpStateReq === PhyStateReq.l2, PhyState.l2),
//                 (io.rdi.lpStateReq === PhyStateReq.retrain, PhyState.retrain)

//             ))  
//         }
//         // l2 unsure, l2 for power management
//         is(PhyState.l2) {
//                 nextState := PhyState.reset
//         }
//         is(PhyState.linkError) {
//             nextState := PriorityMux(Seq(
//                 (io.rdi.lpStateReq === PhyStateReq.nop, PhyState.reset),
//                 (io.rdi.lpStateReq === PhyStateReq.active, PhyState.active),
//                 (io.rdi.lpStateReq === PhyStateReq.l1, PhyState.l1),
//                 (io.rdi.lpStateReq === PhyStateReq.l2, PhyState.l2),
//                 (io.rdi.lpStateReq === PhyStateReq.retrain, PhyState.retrain)
//             ))
//         }
//     }
//     // Following 3 states could be transitioned from any other states
//     // Disabled and LinkError transition occurs also upon remote side band message request
//     // TODO: sideband conditioned transitions
//     // May's understanding: according to p224 priority should be LinkError>Disabled>LinkReset
//     currentState := PriorityMux(Seq(
//         (io.rdi.lpLinkError, PhyState.linkError),
//         (io.rdi.lpStateReq === PhyStateReq.nop, PhyState.reset),
//         (io.rdi.lpStateReq === PhyStateReq.linkReset, PhyState.linkReset),
//         (io.rdi.lpStateReq === PhyStateReq.disabled, PhyState.disabled),
//         (true.B, nextState)
//     ))
//     // FSM End ---------------------------------------------------------------

//     // Sub-Actions Begin ------------------------------------------------------------------
//     // For actions, refer to p250
//     // TODO: clock gating, power management
    
//     def Transition_ResetToActive (): Unit = {
//         // Can transition to active, l1, and l2
//         // Only side band message (dest state) differs
        
//         // 1. drive side band, send message to request active
//         // 2. decode side band message, check if ack from partner, if so, turn on ack
//     }
//     // Sub-Actions End ------------------------------------------------------------------


//     // Datapath Begin ------------------------------------------------------------------
//     when(currentState === PhyState.reset) {

//     }
    
//     // sidebandMessageEncoder = new SidebandMessageEncoder()

//     // def SendSidebandMessage(): Unit{
//     //     sbAfe.txData.valid := true.B
//     //     sbAfe.txData.irdy := !sbAfe.txData.ready
//     //     sbAfe.txData.bits := 5.U
//     // }
// }

// // This module converts a message into SidebandMessageHeader() type
// // to be sent out upon FSM request 
// class SidebandMessageSender extends Module {
//     val io = IO{new Bundle {
//         // // Opcode is 5 bit
//         // val opcode = Input(Bits(5.W))
//         // // MsgCode is 8 bit
//         // val msgCode = Input(Bits(8.W))
//         // // MsgInfo is 16 bit
//         // val msgInfo = Input(Bits(8.W))
//         // // MsgCode is 8 bit
//         // val msgSubCode = Input(Bits(16.W))

//         val opcode = Input(Opcode())
//         // MsgCode is 8 bit
//         val msgCode = Input(MsgCode())
//         // MsgInfo is 16 bit
//         val msgInfo = Input(MsgInfo())
//         // MsgCode is 8 bit
//         val msgSubCode = Input(MsgSubCode())

//         val msgHeaderOut = Output(new SidebandMessageHeader())
//     }} 
//     // val srcid = WireInit(0.U(3.W))
//     val srcid = WireInit(SourceID.DieToDieAdapter)

//     val rsvd_00 = WireInit(0.U(2.W))
//     val rsvd_01 = WireInit(0.U(5.W))
//     // val msgCode = WireInit(0.U(8.W))
//     val rsvd_02 = WireInit(0.U(9.W))
//     // val opcode = WireInit(0.U(5.W)) 
//     val dp = WireInit(0.U(1.W))
//     val cp = WireInit(0.U(1.W))
//     val rsvd_10 = WireInit(0.U(3.W))
//     val dstid = WireInit(0.U(3.W))

//     // Phase 0
//     io.msgHeaderOut.srcid := srcid 
//     io.msgHeaderOut.rsvd_00 := rsvd_00
//     io.msgHeaderOut.rsvd_01 := rsvd_01 
//     io.msgHeaderOut.msgCode := io.msgCode
//     io.msgHeaderOut.rsvd_02 := rsvd_02 
//     io.msgHeaderOut.opcode := io.opcode
//     // Phase 1
//     io.msgHeaderOut.dp := dp 
//     io.msgHeaderOut.cp := cp
//     io.msgHeaderOut.rsvd_10 := rsvd_10 
//     io.msgHeaderOut.dstid := dstid 
//     io.msgHeaderOut.msgInfo := io.msgInfo
//     io.msgHeaderOut.msgSubCode := io.msgInfo
// }

// // This module turns message to be sent into a 32 bit stream
// class SidebandMessageDecoder extends Module {
//     val io = IO(new Bundle {
//         val fire = Input(Bool())
//         val msgIn = Input(Bits(32.W))
//         val msgHeaderOut = Output(new SidebandMessageHeader())
//         val data = Output(Bits(32.W))
//         val phase = Output(Bits(4.W))
//     })
//     val phaseCounter = RegInit(0.U(32.W))
//     io.msgHeaderOut.opcode := io.msgIn(4, 0)
//     // if msb of opcode is 1, it's register access type; otherwise it's message type
//     // when(io.msgHeaderOut.opcode(4) === 0.U){
//     //     // Register type to be implemented
        
//     // }.otherwise{
//         // phase 0
//         io.msgHeaderOut.srcid := io.msgIn(31, 29)
//         io.msgHeaderOut.rsvd_00 := io.msgIn(28, 27)
//         io.msgHeaderOut.rsvd_01 := io.msgIn(26, 22)
//         io.msgHeaderOut.msgCode := io.msgIn(21, 14)
//         io.msgHeaderOut.rsvd_02 := io.msgIn(13, 5)

//         // phase 1
//         io.msgHeaderOut.dp := io.msgIn(31)
//         io.msgHeaderOut.cp := io.msgIn(30)
//         io.msgHeaderOut.rsvd_10 := io.msgIn(29, 27)
//         io.msgHeaderOut.dstid := io.msgIn(26, 24)
//         io.msgHeaderOut.msgInfo := io.msgIn(23, 8)
//         io.msgHeaderOut.msgSubCode := io.msgIn(7, 0)
//     // }
//     when(io.fire) {
//         phaseCounter := phaseCounter + 1.U
//     }.otherwise{
//         phaseCounter := 0.U
//     }
// }

// class SidebandMessageEncoder extends Module {
//     val io = IO(new Bundle{
//         val enable = Input(Bool())
//         val ready = Input(Bool())
//         val msgHeaderIn = Input(new SidebandMessageHeader())
//         val data0 = Input(Bits(32.W))
//         val data1 = Input(Bits(32.W))
//         val msgOut = Output(Bits(32.W))
//         val phase0Val = Output(Bool())
//         val phase1Val = Output(Bool())
//         val phase2Val = Output(Bool())
//         val phase3Val = Output(Bool())
//         val done = Output(Bool())
//     })

//     val phase0 = Cat(
//                     io.msgHeaderIn.srcid.asUInt, 
//                     io.msgHeaderIn.rsvd_00,
//                     io.msgHeaderIn.rsvd_01,
//                     io.msgHeaderIn.msgCode.asUInt,
//                     io.msgHeaderIn.rsvd_02,
//                     io.msgHeaderIn.opcode.asUInt
//                     )
//     val phase1 = Cat(
//                     io.msgHeaderIn.dp,
//                     io.msgHeaderIn.cp,
//                     io.msgHeaderIn.rsvd_10,
//                     io.msgHeaderIn.dstid.asUInt,
//                     io.msgHeaderIn.msgInfo.asUInt,
//                     io.msgHeaderIn.msgSubCode.asUInt
//                     )
//     // Take care of data later
//     // assert (phase0.getWidth == 32.U, "Width not equal to 32")
//     println(s"Width of phase0: ${phase0.getWidth}")
//     println(s"Width of phase1: ${phase1.getWidth}")
//     val counter = RegInit(0.U(4.W))
//     val msgOutReg = RegInit(0.U(32.W))
//     val doneReg = RegInit(false.B)
//     val phase0ValReg = RegInit(false.B)
//     val phase1ValReg = RegInit(false.B)
//     val phase2ValReg = RegInit(false.B)
//     val phase3ValReg = RegInit(false.B)
//     // When asked to send, and other side ready, start 

//     io.done := doneReg
//     io.msgOut := msgOutReg
//     io.phase0Val := phase0ValReg
//     io.phase1Val := phase1ValReg
//     io.phase2Val := phase2ValReg
//     io.phase3Val := phase3ValReg

//     //TODO: [discussion] Souldn't phasexVal be one hot?

//     when(io.enable && io.ready){
//         phase0ValReg := false.B  
//         phase1ValReg := false.B
//         phase2ValReg := false.B
//         phase3ValReg := false.B

//         // send message
//         when(counter === 0.U){
//             msgOutReg := phase0
//             phase0ValReg := true.B
//             counter := counter + 1.U
//         }.elsewhen(counter === 1.U){
//             msgOutReg := phase1
//             phase1ValReg := true.B
//             counter := counter + 1.U
//         }.elsewhen(counter === 2.U){
            
//             // May's question: on p145 where is phases 2&3?
//             // Need to incorporate message types other than "message without data"?
//             // Stefan: Yes, on p153, Messages with Data Payload
//             when(io.msgHeaderIn.opcode === Opcode.MessageWith64bData){
//                 msgOutReg := io.data0
//                 phase2ValReg := true.B
//                 counter := counter + 1.U
//             }.otherwise{
//                 doneReg := true.B
//             }
//         }.elsewhen(counter === 3.U){
//             when(io.msgHeaderIn.opcode === Opcode.MessageWith64bData){
//                 msgOutReg := io.data1
//                 phase3ValReg := true.B
//                 counter := counter + 1.U
//             }.otherwise{
//                 doneReg := true.B
//             }
//         }.otherwise{
//             doneReg := true.B
//         }
//         // when completed and enable deasserted, reset counter, deassert doneReg 
//     }.elsewhen(doneReg && !io.enable){
//         doneReg := false.B
//         counter := 0.U
//         phase0ValReg := false.B
//         phase1ValReg := false.B
//         phase2ValReg := false.B
//         phase3ValReg := false.B
//     }
    
// }