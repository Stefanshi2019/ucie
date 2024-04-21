package edu.berkeley.cs.ucie.digital
package d2dadaptor

import chisel3._
import chisel3.util._
import interfaces._
import sideband._

object LinkInitState extends ChiselEnum{
    val INIT_START = Value(0x0.U(3.W))
    val RDI_BRINGUP = Value(0x1.U(3.W))
    val PARAM_EXCH = Value(0x2.U(3.W))
    val FDI_BRINGUP = Value(0x3.U(3.W))
    val INIT_DONE = Value(0x4.U(3.W))
}
// The FSM of RDI and FDI are identical,
// each is embedded into LogicalPhy and Protocal respectively



// class Fdi (fdiParams: FdiParams) extends Bundle {
//   val lpData = Decoupled3(Bits((8 * fdiParams.width).W))
//   val lp_state_req = Output(UInt(4.W))
//   val lp_rx_active_sts = Output(Bool())
//   val lp_wake_req = Output(Bool())
//   val plData = Flipped(Valid(Bits((8 * fdiParams.width).W))) 
//   val pl_state_sts = Input(UInt(4.W))
//   val pl_inband_pres = Input(Bool())
//   val pl_rx_active_req = Input(Bool())
//   val pl_stallreq = Input(Bool())
//   val pl_wake_ack = Input(Bool())
// }

   

// /** The raw D2D interface (RDI), from the perspective of the D2D Adapter. */
// class Rdi(rdiParams: RdiParams) extends Bundle {
//   val lclk = Input(Clock())
//   val lpData = Decoupled3(Bits((8 * rdiParams.width).W)) // abnormal signal
//   val plData = Flipped(Valid(Bits((8 * rdiParams.width).W))) // abnormal signal
//   val lp_state_req = Output(PhyStateReq())
//   val lp_linkerror = Output(Bool())
//   val pl_state_sts = Input(PhyState())
//   val pl_inband_pres = Input(Bool())
//   val pl_error = Input(Bool())
//   val pl_stallreq = Input(Bool())
//   val lp_stallack = Output(Bool())
//   val pl_clk_req = Input(Bool())
//   val lp_clk_ack = Output(Bool())
//   val lp_wake_req = Output(Bool())
//   val pl_wake_ack = Input(Bool())
// }

class d2dadaptor (
    rdiParams: RdiParams,
    fdiParams: FdiParams
) extends Module {

    val io = IO(new Bundle {
        val fdi = Flipped(new Fdi(fdiParams))
        val rdi = new Rdi(rdiParams)
        val rdiSb = new SbMsgIO() 
    })
    
    
    // adaptor/FDI FSM signals
    val adaptorState = RegInit(PhyState.reset)
    val adaptorState_next   = WireInit(PhyState.reset) 
    // by default, don't change state
    adaptorState_next   := adaptorState 
    when(io.fdi.lp_state_req === PhyStateReq.nop) {
        adaptorState_next := adaptorState 
    }


    // RDI drivers
    val rdi_lp_state_req_reg = RegInit(PhyStateReq.nop) // default needs to be defined based on PhyStateReq
    val rdi_lp_state_req_reg_pipe_1 = RegNext(rdi_lp_state_req_reg)
    val rdi_lp_linkerror_reg = RegInit(false.B)
    val rdi_lp_stallack_reg = RegInit(false.B)
    val rdi_lp_clk_ack_reg = RegInit(false.B)
    val rdi_lp_wake_req_reg = RegInit(false.B)

    io.rdi.lp_state_req := rdi_lp_state_req_reg_pipe_1
    io.rdi.lp_linkerror := rdi_lp_linkerror_reg
    io.rdi.lp_stallack := rdi_lp_stallack_reg
    io.rdi.lp_clk_ack := rdi_lp_clk_ack_reg
    io.rdi.lp_wake_req := rdi_lp_wake_req_reg

    rdi_lp_state_req_reg := PhyStateReq.nop


    // FDI drivers
    val fdi_lpData_trdy_reg = RegInit(false.B)
    val fdi_pl_state_sts_reg = RegInit(PhyState.reset)
    val fdi_pl_inband_pres_reg = RegInit(false.B)
    val fdi_pl_rx_active_req_reg = RegInit(false.B)
    val fdi_pl_stallreq_reg = RegInit(false.B)
    val fdi_pl_wake_ack_reg = RegInit(false.B)
    val fdi_lp_state_req_reg = RegInit(PhyStateReq.nop)

    io.fdi.lpData.ready := fdi_lpData_trdy_reg
    io.fdi.pl_state_sts := adaptorState
    io.fdi.pl_inband_pres := fdi_pl_inband_pres_reg
    io.fdi.pl_rx_active_req := fdi_pl_rx_active_req_reg
    io.fdi.pl_stallreq := fdi_pl_stallreq_reg
    io.fdi.pl_wake_ack := fdi_pl_wake_ack_reg

    // RDI Sideband drivers

    io.rdiSb.tx.handshake.valid := false.B 
    io.rdiSb.tx.opcode := 0.U 
    io.rdiSb.tx.msgCode := 0.U  
    io.rdiSb.tx.msgSubCode := 0.U  
    io.rdiSb.tx.msgInfo := 0.U  
    io.rdiSb.tx.data0 :=  0.U  // assert 0th bit for raw mode
    io.rdiSb.tx.data1 := 0.U // assert 0th bit for raw mode
    
    // FDI sub signals
    val beginStallreqAck = RegInit(false.B)
    
    // Link Init sub signals
    val linkInitState_reg = RegInit(LinkInitState.INIT_START) //3.W
    val sb_lock = RegInit(false.B)
    val linkInit_bringup_req_received = RegInit(false.B)
    val linkInit_bringup_rsp_received = RegInit(false.B)
    val linkInit_start = RegInit(false.B)

    // adaptor/FDI FSM 
    switch(adaptorState) {
        
        is(PhyState.reset) {
            // transition to active
            when(io.fdi.lp_state_req === PhyStateReq.active) { 
                linkInit_start := true.B                    
            }.elsewhen(linkInit_start === true.B && linkInitState_reg === LinkInitState.INIT_DONE){
                adaptorState_next := PhyState.active 
                linkInit_start := false.B 
            }


            // Stage 2: complete - exchange parameters on sideband and mainband training
 
        }
        is(PhyState.active){

            // Stallreq/Ack must be performed when current state is at active
            when(io.fdi.lp_stallack === false.B && fdi_pl_stallreq_reg === false.B) {
                   fdi_pl_stallreq_reg := true.B
                }.elsewhen(io.fdi.lp_stallack === true.B && fdi_pl_stallreq_reg === true.B) {
                    val condition = (io.fdi.lpData.valid && io.fdi.lpData.irdy)
                    when(condition) {
                        printf(p"Assertion fail:  io.fdi.lpData.valid = ${io.fdi.lpData.valid},  io.fdi.lpData.irdy = ${ io.fdi.lpData.irdy}\n")
                    }
                    assert(!condition, "Output must be greater than input")

                    fdi_pl_stallreq_reg := false.B
                    adaptorState_next := PriorityMux(Seq(
                        // Direct transition, no handshake required
                        // transition to reset requires NOP
                        (io.fdi.lp_state_req === PhyStateReq.l1, PhyState.l1),
                        (io.fdi.lp_state_req === PhyStateReq.l2, PhyState.l2),
                        //(Not applicable for CXL Flit Mode with Retry in the Adapter)
                        (io.fdi.lp_state_req === PhyStateReq.retrain, PhyState.retrain)
                    ))
                }

        }
        // DAPM unimplemented, same as L1 transition
        // l1 unsure, l1 for power management
        is(PhyState.l1) {
            when(io.fdi.lp_state_req === PhyStateReq.retrain) {
                adaptorState_next := PhyState.retrain
            }
        }
        is(PhyState.linkReset) {
            adaptorState_next := PriorityMux(Seq(
                // transition to reset requires NOP
                (io.fdi.lp_state_req === PhyStateReq.active, PhyState.active),
                (io.fdi.lp_state_req === PhyStateReq.l1, PhyState.l1),
                (io.fdi.lp_state_req === PhyStateReq.l2, PhyState.l2)
            )) 
        }
        
        is(PhyState.retrain) {
            when(io.fdi.lp_state_req === PhyStateReq.active) {
                adaptorState_next := PhyState.active
            }
        }
        is(PhyState.disabled) {
            
            adaptorState_next := PriorityMux(Seq(
                // transition to reset requires NOP
                (io.fdi.lp_state_req === PhyStateReq.active, PhyState.active),
                (io.fdi.lp_state_req === PhyStateReq.l1, PhyState.l1),
                (io.fdi.lp_state_req === PhyStateReq.l2, PhyState.l2),
                (io.fdi.lp_state_req === PhyStateReq.retrain, PhyState.retrain)

            ))  
        }
        // l2 unsure, l2 for power management
        is(PhyState.l2) {
                adaptorState_next := PhyState.reset
        }
        is(PhyState.linkError) {
            adaptorState_next := PriorityMux(Seq(
                (io.fdi.lp_state_req === PhyStateReq.active, PhyState.active),
                (io.fdi.lp_state_req === PhyStateReq.l1, PhyState.l1),
                (io.fdi.lp_state_req === PhyStateReq.l2, PhyState.l2),
                (io.fdi.lp_state_req === PhyStateReq.retrain, PhyState.retrain)
            ))
        }
    }
    


    // send Sb message to link partner when doing state transitions, the following do not require handshake 
    when (io.fdi.lp_linkerror || io.fdi.lp_state_req === PhyStateReq.disabled || io.fdi.lp_state_req === PhyStateReq.linkReset){

        // Stallreq/Ack must be performed when current state is at active
        when(adaptorState === PhyState.active && 
        (io.fdi.lp_state_req === PhyStateReq.disabled || io.fdi.lp_state_req === PhyStateReq.linkReset) 
        ) {
            when(io.fdi.lp_stallack === false.B && fdi_pl_stallreq_reg === false.B) {
                fdi_pl_stallreq_reg := true.B
                sb_lock := true.B
            }.elsewhen(io.fdi.lp_stallack === true.B && fdi_pl_stallreq_reg === true.B) {
                val condition = (io.fdi.lpData.valid && io.fdi.lpData.irdy)
                when(condition) {
                    printf(p"Assertion fail:  io.fdi.lpData.valid = ${io.fdi.lpData.valid},  io.fdi.lpData.irdy = ${ io.fdi.lpData.irdy}\n")
                }
                assert(!condition, "Output must be greater than input")

                fdi_pl_stallreq_reg := false.B
                sb_lock := false.B
            }
        }
        when(sb_lock === false.B) {
            io.rdiSb.tx.handshake.valid := true.B 
            io.rdiSb.tx.opcode := Opcode.MessageWithoutData
            io.rdiSb.tx.msgCode := MsgCode.LinkMgmt_Adaptor0_Req

            // if curr state is reset, last req must be nop, p250, p253
            when(io.fdi.lp_state_req === PhyStateReq.disabled && 
            ((adaptorState === PhyState.reset && fdi_lp_state_req_reg === PhyStateReq.nop) || (adaptorState =/= PhyState.reset))
            ){
                io.rdiSb.tx.msgSubCode := MsgSubCode.Disable
            }
            // p252, p255
            when(io.fdi.lp_state_req === PhyStateReq.linkReset){
                io.rdiSb.tx.msgSubCode := MsgSubCode.LinkReset
            }   

            // Link Error should assert lp_linkerror to RDI, ask logphy to send sb message, p253, p256
            when(io.fdi.lp_linkerror){
                io.rdiSb.tx.handshake.valid := false.B 
                rdi_lp_linkerror_reg := true.B
            }
        }
    }

    // If received a sideband message
    when(io.rdiSb.rx.bits.valid === true.B && io.rdiSb.rx.opcode === Opcode.MessageWithoutData) {
        // If received sb msg state req, send ack, then transition
        when(io.rdiSb.rx.msgCode === MsgCode.LinkMgmt_Adaptor0_Req) {
            // set default message format
            io.rdiSb.tx.handshake.valid := true.B 
            io.rdiSb.tx.opcode := Opcode.MessageWithoutData
            io.rdiSb.tx.msgCode := MsgCode.LinkMgmt_Adaptor0_Rsp 
            // as the sender, need to request rdi to transition to disabled, what do when received ack?
            when(io.rdiSb.rx.msgSubCode === MsgSubCode.Disable){
                io.rdiSb.tx.msgSubCode := MsgSubCode.Disable
                adaptorState_next := PhyState.disabled
                rdi_lp_state_req_reg :=  PhyStateReq.disabled
            }
            // as the sender, need to request rdi to transition to linkreset, what do when received ack?
            when(io.rdiSb.rx.msgSubCode === MsgSubCode.LinkReset){
                io.rdiSb.tx.msgSubCode := MsgSubCode.LinkReset  
                adaptorState_next := PhyState.linkReset 
                rdi_lp_state_req_reg :=  PhyStateReq.linkReset

            } 
        // If received sb msg state ack, transition, no message
        }.elsewhen (io.rdiSb.rx.msgCode === MsgCode.LinkMgmt_Adaptor0_Rsp) {
            when(io.rdiSb.rx.msgSubCode === MsgSubCode.Disable){
                adaptorState_next := PhyState.disabled
            }
            when(io.rdiSb.rx.msgSubCode === MsgSubCode.LinkReset){
                adaptorState_next := PhyState.linkReset 
            }  
        }.otherwise {
            printf("Received sb message, unknown content")
        }
    }

    // if received an stallreq
    when(io.rdi.pl_stallreq === true.B && io.rdi.lp_stallack === false.B) {
        io.rdi.lpData.valid := false.B 
        io.rdi.lpData.irdy := false.B 
        io.rdi.lp_stallack := true.B
    }.elsewhen(io.rdi.pl_stallreq === false.B && io.rdi.lp_stallack === true.B) {
        io.rdi.lp_stallack := false.B
    }
    // FSM End ---------------------------------------------------------------



   
    // Link Init sub state transitions, operates only when fdi state in reset
    switch(linkInitState_reg){
        is(LinkInitState.INIT_START){
            // TODO: what's going here?
            // Only begin when instructed
            when(linkInit_start) {
                linkInitState_reg := LinkInitState.RDI_BRINGUP
            }
        }

// The FSM of RD
        is(LinkInitState.RDI_BRINGUP){
            // Wait for logphy to complete handshake
            when(io.rdi.pl_inband_pres) {
                rdi_lp_state_req_reg := PhyStateReq.active
                // should also remove clock gating, but ignore for now. p.s. should be simple
                when(io.rdi.pl_state_sts === PhyState.active){
                    rdi_lp_state_req_reg := PhyStateReq.nop // deassert state change req
                    linkInitState_reg := LinkInitState.PARAM_EXCH 
                }
            } 
        }

        is(LinkInitState.PARAM_EXCH){
            
            when (sb_lock === false.B) {
                io.rdiSb.tx.handshake.valid := true.B 
                io.rdiSb.tx.opcode := Opcode.MessageWith64bData
                io.rdiSb.tx.msgCode := WplMsgCode.AdvCap
                io.rdiSb.tx.msgSubCode := WplMsgSubCode.Adaptor 
                io.rdiSb.tx.data0 := 1.U // assert 0th bit for raw mode
                sb_lock := true.B 
            }.otherwise {
                when(io.rdiSb.rx.bits.valid === true.B) {
                    when(
                        io.rdiSb.rx.opcode === Opcode.MessageWith64bData &&
                        io.rdiSb.rx.msgCode === WplMsgCode.AdvCap &&
                        io.rdiSb.rx.msgSubCode === WplMsgSubCode.Adaptor 
                    ) {
                        io.rdiSb.tx.handshake.valid := true.B 
                        io.rdiSb.tx.opcode := Opcode.MessageWith64bData
                        io.rdiSb.tx.msgCode := WplMsgCode.FinCap
                        io.rdiSb.tx.msgSubCode := WplMsgSubCode.Adaptor 
                        io.rdiSb.tx.data0 := 1.U // assert 0th bit for raw mode
                        sb_lock := false.B
                        linkInitState_reg := LinkInitState.FDI_BRINGUP
                    }.otherwise{
                        printf("Unknown behavior in LINKINIT PARAM EXCHANGE\n")
                    } 
                }
                
            }

        }

        is(LinkInitState.FDI_BRINGUP){

            // adaptor to protocol, ungate clock
            fdi_pl_inband_pres_reg := true.B
            // protocol to adaptor, request fdi state transition to Active, trigger remote link partner handshake 
            when(io.fdi.lp_state_req === PhyStateReq.active){
                // check if received an active rsp yet
                when(linkInit_bringup_rsp_received === false.B) {
                    // check if sent an active req yet, if not, send one 
                    when(sb_lock === false.B){
                        io.rdiSb.tx.handshake.valid := true.B 
                        io.rdiSb.tx.opcode := Opcode.MessageWithoutData
                        io.rdiSb.tx.msgCode := MsgCode.LinkMgmt_Adaptor0_Req
                        io.rdiSb.tx.msgSubCode := MsgSubCode.Active 
                        sb_lock := true.B 
                    }.otherwise{
                        // then wait for response, if received, unleash sb_lock
                        when(io.rdiSb.rx.bits.valid === true.B &&
                            io.rdiSb.rx.opcode === Opcode.MessageWith64bData &&
                            io.rdiSb.rx.msgCode === MsgCode.LinkMgmt_Adaptor0_Rsp &&
                            io.rdiSb.rx.msgSubCode === MsgSubCode.Active
                        ) {
                            linkInit_bringup_rsp_received := true.B
                            sb_lock := false.B 
                        }
                    }
                }
                when(linkInit_bringup_req_received === false.B) {
                    // check if received an active req yet
                    when(io.rdiSb.rx.bits.valid === true.B &&
                        io.rdiSb.rx.opcode === Opcode.MessageWith64bData &&
                        io.rdiSb.rx.msgCode === MsgCode.LinkMgmt_Adaptor0_Rsp &&
                        io.rdiSb.rx.msgSubCode === MsgSubCode.Active
                    ) {
                        // if received, assert rx active req to protocol
                        fdi_pl_rx_active_req_reg := true.B 
                        // once received rsp from protocol, complete req reception, deassert active req to protocol
                        when(io.fdi.lp_rx_active_sts === true.B){
                            io.fdi.pl_rx_active_req := false.B 
                            linkInit_bringup_req_received := true.B 
                        }
                    }
                }
                // once received both signals 
                when(linkInit_bringup_req_received && linkInit_bringup_rsp_received){
                    linkInitState_reg := LinkInitState.INIT_DONE 
                }
            }

        }

        is(LinkInitState.INIT_DONE){
            linkInitState_reg := LinkInitState.INIT_START  
        } 
    }





}