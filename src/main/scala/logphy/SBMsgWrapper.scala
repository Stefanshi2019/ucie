package edu.berkeley.cs.ucie.digital
package logphy

import chisel3._
import chisel3.util._
import interfaces._
import sideband._

class SBMsgWrapperTrainIO(
) extends Bundle {
  val msgReq = Flipped(Decoupled(new MessageRequest))
  val msgReqStatus = Decoupled(new MessageRequestStatus)
}

/** TODO: implementation */
class SBMsgWrapper( //TODO: cp, dp, messages with data
    afeParams: AfeParams,
) extends Module {
  val io = IO(new Bundle {
    val trainIO = new SBMsgWrapperTrainIO()
    val msgHeaderIO = new SBMsgWrapperHeaderIO()
    // val laneIO = new SidebandLaneIO(afeParams)
    val sbAfe = new SidebandAfeIo(afeParams)
    // val opCode = Input(Opcode)
    // val srcid = Input(SourceID)
    // val msgCode = Input(MsgCode)
    // val msgInfo = Input(MsgInfo)
    // val msgSubCode = Input(MsgSubCode)
  })

  private object State extends ChiselEnum {
    val IDLE, EXCHANGE, REQ, RESP, WAIT_ACK_SUCCESS, WAIT_ACK_ERR = Value
  }

  private object SubState extends ChiselEnum {
    val SEND_OR_RECEIVE_MESSAGE_0, SEND_OR_RECEIVE_MESSAGE_1, SEND_OR_RECEIVE_DATA_0, SEND_OR_RECEIVE_DATA_1 = Value
  }

  private val currentState = RegInit(State.IDLE)
  private val sendSubState = RegInit(SubState.SEND_OR_RECEIVE_MESSAGE_0)
  private val receiveSubState = RegInit(SubState.SEND_OR_RECEIVE_MESSAGE_0)
  private val timeoutCounter = RegInit(0.U(64.W))

  private val nextState = Wire(currentState)
  currentState := nextState
  when(currentState =/= nextState) {
    sendSubState := SubState.SEND_OR_RECEIVE_MESSAGE_0
    receiveSubState := SubState.SEND_OR_RECEIVE_MESSAGE_0
    timeoutCounter := 0.U
  }

  private val nextSendSubState = Wire(sendSubState)
  sendSubState := nextSendSubState
  // msgRdy only gets set upon transition to sends
  // TODO: ?
  when((sendSubState =/= nextSendSubState) || (currentState =/= nextState)) {
    msgRdy := true.B
  }

  val sendEnable = RegInit(false.B) //TODO: logic
  val msgRdy = RegInit(false.B)
  // val opCode = RegInit(Opcode.MessageWithoutData)
  // val srcid = RegInit(SourceID.DieToDieAdapter)
  // val msgCode = RegInit(MsgCode.Nop)
  // val msgInfo = RegInit(MsgInfo.RegularResponse)
  // val msgSubCode = RegInit(MsgSubCode.Crd)

  // To convert 1-bit sideband rx to 128-bit packet header
  private val sidebandRxWidthCoupler64 = new DataWidthCoupler(
    DataWidthCouplerParams(
      inWidth = afeParams.sbSerializerRatio,
      outWidth = 128, //TODO: currently all 128
    ),
  )

  // To convert 32-bit packet header to 1-bit sideband tx
  private val sidebandTxWidthCoupler64 = new DataWidthCoupler(
    DataWidthCouplerParams(
      inWidth = 32,
      outWidth = afeParams.sbSerializerRatio,
    ),
  )

  private val sbWrapper = new SidebandWrapper()

  // Data flow:
  // msgwrapper(this) <--enable, opcode, etc--> sbWrapper <--32 bit headers--> width decoupler <--1 bit sb data--> sbAfe
  sbWrapper.io.sendEnable := sendEnable
  sbWrapper.io.msgReady := msgRdy
  sbWrapper.io.sbTx <> sidebandTxWidthCoupler64.io.in.bits
  sbWrapper.io.sbRx <> sidebandRxWidthCoupler64.io.out.bits
  sbWrapper.io.opCode := io.msgHeaderIO.opCode
  sbWrapper.io.srcid := io.msgHeaderIO.srcid
  sbWrapper.io.msgCode := io.msgHeaderIO.msgCode
  sbWrapper.io.msgInfo := io.msgHeaderIO.msgInfo
  sbWrapper.io.msgSubCode := io.msgHeaderIO.msgSubCode
  sbWrapper.io.data := io.msgHeaderIO.data

  private val msgHeader = WireInit(0.U(128.W))
  msgHeader := sbWrapper.io.msgHeader
  
  io.sbAfe.rxData <> sidebandRxWidthCoupler64.io.in
  io.sbAfe.txData <> sidebandTxWidthCoupler64.io.out

  private val currentReq = RegInit(0.U((new MessageRequest).msg.getWidth.W))
  private val currentReqHasData = RegInit(false.B)
  private val currentReqTimeoutMax = RegInit(0.U(64.W))

  // private val dataOut = RegInit(0.U(64.W))
  io.trainIO.msgReqStatus.bits.msgInfo := msgHeader.msgInfo
  io.trainIO.msgReqStatus.bits.data := Cat(msgHeader.data1, msgHeader.data0) // TODO: MSBs or LSBs?
  io.trainIO.msgReqStatus.bits.status := Mux(
    currentState === State.WAIT_ACK_SUCCESS,
    MessageRequestStatusType.SUCCESS,
    MessageRequestStatusType.ERR,
  )

  switch(currentState) {
    is(State.IDLE) {
      sendEnable := false.B
      io.trainIO.msgReq.ready := true.B
      when(io.trainIO.msgReq.fire) {
        // currentReq := io.trainIO.msgReq.bits.msg
        currentReqHasData := io.trainIO.msgReq.bits.msgTypeHasData
        currentReqTimeoutMax := io.trainIO.msgReq.bits.timeoutCycles
        switch(io.trainIO.msgReq.bits.reqType) {
          is(MessageRequestType.MSG_REQ) {
            nextState := State.EXCHANGE
          }
          is(MessageRequestType.MSG_RESP) {
            nextState := State.EXCHANGE
          }
          is(MessageRequestType.MSG_EXCH) {
            nextState := State.EXCHANGE
          }
        }
      }
    }
    is(State.EXCHANGE) {
      sendEnable := true.B

      def messageIsEqual(rxmsg: UInt, op: Opcode, sub: MsgSubCode, code: MsgCode): Bool = {

        /** opcode */ //TODO: double check bit positions: LSB/MSB?
        (rxmsg(4, 0) === Opcode) &&
        /** subcode */
        (rxmsg(21, 14) === sub) &&
        /** code */
        (rxmsg(39, 32) === code)
      }

      /** send message over sideband. TODO: keeps sending until timeout */
      // TODO: misaligned phases with sbwrapper?
      switch(sendSubState) {
        is(SubState.SEND_OR_RECEIVE_MESSAGE_0) {

          // Only start coupling data when encoder readily outputs 32 bit packet
          // and when the coupler has not started (avoid repeatedly sending to it)
          // TODO: shouldn't matter because coupler would be busy anyways?
          when(sbWrapper.io.phase0Val && !sidebandTxWidthCoupler64.io.in.fire) {
            msgRdy := false.B
            sidebandTxWidthCoupler64.io.in.valid := true.B
          }.otherwise {
            sidebandTxWidthCoupler64.io.in.valid := false.B
          }

          // When the tx decoupler receives the 32-bit phase0 data, we wait for it to be done and start crafting phase1
          when(sidebandTxWidthCoupler64.io.in.fire) {

          }
          when(sidebandTxWidthCoupler64.io.out.fire) { // TODO: change this condition to sbWrapper.msgDone?
            nextSendSubState := SubState.SEND_OR_RECEIVE_MESSAGE_1
          }
        }
        is(SubState.SEND_OR_RECEIVE_MESSAGE_1) {

          when(sbWrapper.io.phase1Val && !sidebandTxWidthCoupler64.io.in.fire) {
            msgRdy := false.B
            sidebandTxWidthCoupler64.io.in.valid := true.B
          }.otherwise {
            sidebandTxWidthCoupler64.io.in.valid := false.B
          }

          // When the tx decoupler receives the 32-bit phase0 data, we wait for it to be done and start crafting phase2
          when(sidebandTxWidthCoupler64.io.in.fire) {

          }
          when(sidebandTxWidthCoupler64.io.out.fire) {
            nextSendSubState := Mux(
              currentReqHasData,
              SubState.SEND_OR_RECEIVE_DATA_0,
              SubState.SEND_OR_RECEIVE_MESSAGE_0
            )
          }
        }
        is(SubState.SEND_OR_RECEIVE_DATA_0) {
          when(sbWrapper.io.phase2Val && !sidebandTxWidthCoupler64.io.in.fire) {
            msgRdy := false.B
            sidebandTxWidthCoupler64.io.in.valid := true.B
          }.otherwise {
            sidebandTxWidthCoupler64.io.in.valid := false.B
          }

          // sidebandTxWidthCoupler64.io.in.bits := currentReq(128, 64)
          when(sidebandTxWidthCoupler64.io.out.fire) {
            nextSendSubState := SubState.SEND_OR_RECEIVE_DATA_1
          }
        }
        is(SubState.SEND_OR_RECEIVE_DATA_1) {
          when(sbWrapper.io.phase3Val && !sidebandTxWidthCoupler64.io.in.fire) {
            msgRdy := false.B
            sidebandTxWidthCoupler64.io.in.valid := true.B
          }.otherwise {
            sidebandTxWidthCoupler64.io.in.valid := false.B
          }

          // sidebandTxWidthCoupler64.io.in.bits := currentReq(128, 64)
          when(sidebandTxWidthCoupler64.io.out.fire) {
            nextSendSubState := SubState.SEND_OR_RECEIVE_DATA_1 //TODO: what if send is done?
          }
        }
      }

      /** if receive message, move on */
      switch(receiveSubState) {
        is(SubState.SEND_OR_RECEIVE_MESSAGE) {
          sidebandRxWidthCoupler64.io.out.ready := true.B
          when(sidebandRxWidthCoupler64.io.out.fire) {
            when(
              messageIsEqual(
                msgHeader,
                io.msgHeaderIO.opCode,
                io.msgHeaderIO.msgSubCode,
                io.msgHeaderIO.msgCode
              ),
            ) {
              when(currentReqHasData) {
                receiveSubState := SubState.SEND_OR_RECEIVE_DATA
              }.otherwise {
                nextState := State.WAIT_ACK_SUCCESS
              }
              nextState := State.WAIT_ACK_SUCCESS
            }
          }
        }
        is(SubState.SEND_OR_RECEIVE_DATA) {
          // sidebandRxWidthCoupler64.io.out.ready := true.B
          // when(sidebandRxWidthCoupler64.io.out.fire) {
          //   dataOut := sidebandRxWidthCoupler64.io.out.bits
          //   nextState := State.WAIT_ACK_SUCCESS
          // }
        }
      }

      /** timeout logic */
      timeoutCounter := timeoutCounter + 1.U
      when(timeoutCounter === currentReqTimeoutMax) {
        nextState := State.WAIT_ACK_ERR
      }

    }
    is(State.WAIT_ACK_SUCCESS) {
      sendEnable := false.B
      io.trainIO.msgReqStatus.valid := true.B
      when(io.trainIO.msgReqStatus.fire) {
        nextState := State.IDLE
      }
    }
    is(State.WAIT_ACK_ERR) {
      sendEnable := false.B
      io.trainIO.msgReqStatus.valid := true.B
      when(io.trainIO.msgReqStatus.fire) {
        nextState := State.IDLE
      }
    }
  }

}
