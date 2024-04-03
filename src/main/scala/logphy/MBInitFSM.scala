package edu.berkeley.cs.ucie.digital
package logphy

import interfaces._
import chisel3._
import chisel3.util._
import sideband.{SBM, SBMessage_factory}

// As per spec p87, exchanged parameters during MBINIT.PARAM:
case class MBTrainingParams(
    voltageSwing: Int = 0, // 5 bits
    maximumDataRate: Int = 0, // 4 bits. TODO: find source from Link Capability register
    clockMode: ClockModeParam.Type = ClockModeParam.strobe,
    clockPhase: Boolean = false,
    moduleId: Int = 0,
    ucieAx32: Boolean = false,
)

class MBInitFSM(
    linkTrainingParams: LinkTrainingParams,
    trainingParams: MBTrainingParams,
    afeParams: AfeParams,
) extends Module {

  val io = IO(new Bundle {
    // TODO: needs trigger?
    val sbTrainIO = new Flipped(SBMsgWrapperTrainIO) //TODO: confused about the io direction here..
    val sbMsgHeaderIO = new Flipped(SBMsgWrapperHeaderIO)
    val done = Output(Bool())
    val error = Output(Bool())
  })
  private object State extends ChiselEnum {
    val PARAM, CAL, REPAIR_CLK, REPAIR_VAL, IDLE, ERR = Value
  }

  private object ParamSubState extends ChiselEnum {
    val SEND_REQ, WAIT_REQ, SEND_RESP, WAIT_RESP = Value
  }

  private object RepairClkSubState extends ChiselEnum {
    val SEND_INIT_REQ, WAIT_INIT_REQ, SEND_INIT_RESP, WAIT_INIT_RESP, SEND_PATTERN, DETECT_PATTERN, SEND_RESULT_REQ, WAIT_RESULT_REQ, SEND_RESULT_RESP, WAIT_RESULT_RESP, SEND_DONE_REQ, WAIT_DONE_REQ, SEND_DONE_RESP, WAIT_DONE_RESP = Value
  }

  private val state = RegInit(State.PARAM)
  private val nextState = Wire(state)
  private val paramSubState = RegInit(ParamSubState.SEND_REQ)
  when(nextState === State.PARAM) {
    paramSubState := ParamSubState.SEND_REQ
  }
  private val repairClkSubState = RegInit(RepairClkSubState.SEND_INIT_REQ)
  when(nextState === State.REPAIR_CLK) {
    repairClkSubState := RepairClkSubState.SEND_INIT_REQ
  }

  when(reset.asBool) {
    nextState := State.PARAM
  }
  io.done := nextState === State.IDLE || nextState === State.ERR
  io.error := state === State.ERR
  state := nextState

  /** Initialize params */
  private val voltageSwing = RegInit(
    trainingParams.voltageSwing.U(5.W),
  )
  private val maxDataRate = RegInit(
    trainingParams.maximumDataRate.U(4.W),
  )
  private val clockMode = RegInit(
    trainingParams.clockMode,
  )
  private val clockPhase = RegInit(
    trainingParams.clockPhase.B,
  )
  // TODO: not implemented
  private val moduleId = RegInit(
    trainingParams.moduleId.U(2.W),
  )
  // TODO: not implemented
  private val ucieAx32 = RegInit(
    trainingParams.ucieAx32.B,
  )

  val sbClockFreq =
    linkTrainingParams.sbClockFreqAnalog / afeParams.sbSerializerRatio

  switch(state) {
    is(State.PARAM) {

      /** TODO: where am i actually setting up the params? */
      // def formParamsReqMsg(
      //     req: Bool,
      //     voltageSwing: UInt,
      //     maxDataRate: UInt,
      //     clockMode: ClockModeParam.Type,
      //     clockPhase: Bool,
      //     moduleId: UInt,
      //     UCIeAx32: Bool,
      // ): MessageRequest = {
      //   val data = UInt(64.W)
      //   val msgReq = new MessageRequest()
      //   data := Cat(
      //     0.U(50.W),
      //     UCIeAx32,
      //     moduleId(1, 0),
      //     clockPhase,
      //     clockMode.asUInt,
      //     voltageSwing(4, 0),
      //     maxDataRate(3, 0),
      //   )
      //   msgReq.msg := SBMessage_factory(
      //     Mux(req, SBM.MBINIT_PARAM_CONFIG_REQ, SBM.MBINIT_PARAM_CONFIG_RESP),
      //     "PHY",
      //     false,
      //     "PHY",
      //     data,
      //   )
      //   msgReq.msgTypeHasData := true.B
      //   msgReq.timeoutCycles := (0.008 * sbClockFreq).toInt.U
      //   msgReq.reqType := Mux(
      //     req,
      //     MessageRequestType.MSG_REQ,
      //     MessageRequestType.MSG_RESP,
      //   )
      //   msgReq
      // }

      def formParamData(
          req: Bool,
          voltageSwing: UInt,
          maxDataRate: UInt,
          clockMode: ClockModeParam.Type,
          clockPhase: Bool,
          moduleId: UInt,
          UCIeAx32: Bool,
      ): UInt = Mux(
        req,
        Cat(
          0.U(50.W),
          UCIeAx32,
          moduleId(1, 0),
          clockPhase,
          clockMode.asUInt,
          voltageSwing(4, 0),
          maxDataRate(3, 0),
        ),
        Cat(
          0.U(50.W),
          UCIeAx32,
          moduleId(1, 0),
          clockPhase,
          clockMode.asUInt,
          0.U(5.W),
          maxDataRate(3, 0),
        )
      )
        

      val reqData = RegInit(0.U(64.W))
      val reqMsgInfo = RegInit(0.U(16.W))

      switch(paramSubState) {
        is(ParamSubState.SEND_REQ) {
          // Fill in request fields
          io.sbTrainIO.msgReq.valid := true.B
          io.sbTrainIO.msgReq.bits.reqType := MessageRequestType.MSG_REQ
          io.sbTrainIO.msgReq.bits.timeoutCycles := (
            0.008 * sbClockFreq,
          ).toInt.U
          io.sbTrainIO.msgReq.bits.msgTypeHasData := true.B

          // Fill in msg fields
          io.sbMsgHeaderIO.opCode := Opcode.MessageWith64bData
          io.sbMsgHeaderIO.srcid := SourceID.PhysicalLayer
          io.sbMsgHeaderIO.msgCode := MsgCode.MbInitReq
          io.sbMsgHeaderIO.msgInfo := MsgInfo.RegularResponse
          io.sbMsgHeaderIO.msgSubCode := MbInitMsgSubCode.MbInitParam
          io.sbMsgHeaderIO.data := formParamData(
            true.B,
            trainingParams.voltageSwing,
            trainingParams.maximumDataRate,
            trainingParams.clockMode,
            trainingParams.clockPhase,
            trainingParams.moduleId,
            trainingParams.ucieAx32
          )

          when(io.sbTrainIO.msgReq.fire) {
            paramSubState := ParamSubState.WAIT_REQ
          }
        }
        is(ParamSubState.WAIT_REQ) {// Noe that we don't check opcode/code/subcode here becuase they are checked in sbmsgwrapper
          io.sbTrainIO.msgReq.valid := false.B
          io.sbTrainIO.msgReqStatus.ready := true.B
          when(io.sbTrainIO.msgReqStatus.fire) {
            reqData := io.sbTrainIO.msgReqStatus.bits.data
            when(
              io.sbTrainIO.msgReqStatus.bits.status === MessageRequestStatusType.ERR,
            ) {
              nextState := State.ERR
            }.otherwise {
              paramSubState := ParamSubState.SEND_RESP
            }
          }
        }
        is(ParamSubState.SEND_RESP) {
          io.sbTrainIO.msgReq.valid := true.B
          val exchangedMaxDataRate = UInt(4.W)
          exchangedMaxDataRate := Mux(
            trainingParams.maximumDataRate >= reqData(3, 0),
            reqData(3, 0),
            trainingParams.maximumDataRate,
          )

          // Fill in request fields
          io.sbTrainIO.msgReq.bits.reqType := MessageRequestType.MSG_RESP
          io.sbTrainIO.msgReq.bits.timeoutCycles := (
            0.008 * sbClockFreq,
          ).toInt.U
          io.sbTrainIO.msgReq.bits.msgTypeHasData := true.B

          // Fill in msg fields
          io.sbMsgHeaderIO.opCode := Opcode.MessageWith64bData
          io.sbMsgHeaderIO.srcid := SourceID.PhysicalLayer
          io.sbMsgHeaderIO.msgCode := MsgCode.MbInitResp
          io.sbMsgHeaderIO.msgInfo := MsgInfo.RegularResponse
          io.sbMsgHeaderIO.msgSubCode := MbInitMsgSubCode.MbInitParam
          io.sbMsgHeaderIO.data := formParamData(
            false.B,
            trainingParams.voltageSwing,
            exchangedMaxDataRate,
            trainingParams.clockMode,
            trainingParams.clockPhase,
            trainingParams.moduleId,
            trainingParams.ucieAx32
          )
          // io.sbTrainIO.msgReq.bits := formParamsReqMsg(
          //   false.B,
          //   0.U,
          //   exchangedMaxDataRate,
          //   ClockModeParam(reqData(9, 9)),
          //   reqData(10, 10).asBool,
          //   0.U,
          //   0.B,
          // )
        }
        is(ParamSubState.WAIT_RESP) {
          io.sbTrainIO.msgReq.valid := false.B
          io.sbTrainIO.msgReqStatus.ready := true.B
          when(io.sbTrainIO.msgReqStatus.fire) {
            when(
              io.sbTrainIO.msgReqStatus.bits.status === MessageRequestStatusType.ERR,
            ) {
              nextState := State.ERR
            }.otherwise {
              nextState := State.REPAIR_CLK
            }
          }
        }
      }

    }

    // is(State.CAL) {}
    // NOTE: calibration logic not implemented here

    is(State.REPAIR_CLK) {
      switch(repairClkSubState) {
        is(RepairClkSubState.SEND_INIT_REQ) {
          // Fill in request fields
          io.sbTrainIO.msgReq.valid := true.B
          io.sbTrainIO.msgReq.bits.reqType := MessageRequestType.MSG_REQ
          io.sbTrainIO.msgReq.bits.timeoutCycles := (
            0.008 * sbClockFreq,
          ).toInt.U
          io.sbTrainIO.msgReq.bits.msgTypeHasData := false.B

          // Fill in msg fields
          io.sbMsgHeaderIO.opCode := Opcode.MessageWithoutData
          io.sbMsgHeaderIO.srcid := SourceID.PhysicalLayer
          io.sbMsgHeaderIO.msgCode := MsgCode.MbInitReq
          io.sbMsgHeaderIO.msgInfo := MsgInfo.RegularResponse
          io.sbMsgHeaderIO.msgSubCode := MbInitMsgSubCode.MbInitRepClkInit

          when(io.sbTrainIO.msgReq.fire) {
            repairClkSubState := RepairClkSubState.WAIT_INIT_REQ
          }
        }
        is(RepairClkSubState.WAIT_INIT_REQ) {
          io.sbTrainIO.msgReq.valid := false.B
          io.sbTrainIO.msgReqStatus.ready := true.B
          when(io.sbTrainIO.msgReqStatus.fire) {
            when(
              io.sbTrainIO.msgReqStatus.bits.status === MessageRequestStatusType.ERR,
            ) {
              nextState := State.ERR
            }.otherwise {
              repairClkSubState := RepairClkSubState.SEND_INIT_RESP
            }
          }
        }
        is(RepairClkSubState.SEND_INIT_RESP) {
          // Fill in request fields
          io.sbTrainIO.msgReq.valid := true.B
          io.sbTrainIO.msgReq.bits.reqType := MessageRequestType.MSG_RESP
          io.sbTrainIO.msgReq.bits.timeoutCycles := (
            0.008 * sbClockFreq,
          ).toInt.U
          io.sbTrainIO.msgReq.bits.msgTypeHasData := false.B

          // Fill in msg fields
          io.sbMsgHeaderIO.opCode := Opcode.MessageWithoutData
          io.sbMsgHeaderIO.srcid := SourceID.PhysicalLayer
          io.sbMsgHeaderIO.msgCode := MsgCode.MbInitResp
          io.sbMsgHeaderIO.msgInfo := MsgInfo.RegularResponse
          io.sbMsgHeaderIO.msgSubCode := MbInitMsgSubCode.MbInitRepClkInit

          when(io.sbTrainIO.msgReq.fire) {
            repairClkSubState := RepairClkSubState.WAIT_INIT_REQ
          }
        }
        is(RepairClkSubState.WAIT_INIT_RESP) {
          io.sbTrainIO.msgReq.valid := false.B
          io.sbTrainIO.msgReqStatus.ready := true.B
          when(io.sbTrainIO.msgReqStatus.fire) {
            when(
              io.sbTrainIO.msgReqStatus.bits.status === MessageRequestStatusType.ERR,
            ) {
              nextState := State.ERR
            }.otherwise {
              repairClkSubState := RepairClkSubState.SEND_PATTERN
            }
          }
        }
        is(RepairClkSubState.SEND_PATTERN) {
          //TODO: to do this, mbAfe has to include clk_n, clk_p and track...
          //skipping the pattern here. Can use pattern generator later
          repairClkSubState := RepairClkSubState.WAIT_PATTERN
        }
        is(RepairClkSubState.WAIT_PATTERN) {
          //TODO: Same as send pattern above
          repairClkSubState := RepairClkSubState.SEND_RESULT_REQ
        }
        is(RepairClkSubState.SEND_RESULT_REQ) {
          // Fill in request fields
          io.sbTrainIO.msgReq.valid := true.B
          io.sbTrainIO.msgReq.bits.reqType := MessageRequestType.MSG_REQ
          io.sbTrainIO.msgReq.bits.timeoutCycles := (
            0.008 * sbClockFreq,
          ).toInt.U
          io.sbTrainIO.msgReq.bits.msgTypeHasData := false.B

          // Fill in msg fields
          io.sbMsgHeaderIO.opCode := Opcode.MessageWithoutData
          io.sbMsgHeaderIO.srcid := SourceID.PhysicalLayer
          io.sbMsgHeaderIO.msgCode := MsgCode.MbInitReq
          io.sbMsgHeaderIO.msgInfo := MsgInfo.RegularResponse
          io.sbMsgHeaderIO.msgSubCode := MbInitMsgSubCode.MbInitRepClkRes

          when(io.sbTrainIO.msgReq.fire) {
            repairClkSubState := RepairClkSubState.WAIT_RESULT_REQ
          }
        }
        is(RepairClkSubState.WAIT_RESULT_REQ) {
          io.sbTrainIO.msgReq.valid := false.B
          io.sbTrainIO.msgReqStatus.ready := true.B
          when(io.sbTrainIO.msgReqStatus.fire) {
            when(
              io.sbTrainIO.msgReqStatus.bits.status === MessageRequestStatusType.ERR,
            ) {
              nextState := State.ERR
            }.otherwise {
              repairClkSubState := RepairClkSubState.SEND_RESULT_RESP
            }
          }
        }
        is(RepairClkSubState.SEND_RESULT_RESP) {
          // Fill in request fields
          io.sbTrainIO.msgReq.valid := true.B
          io.sbTrainIO.msgReq.bits.reqType := MessageRequestType.MSG_REQ
          io.sbTrainIO.msgReq.bits.timeoutCycles := (
            0.008 * sbClockFreq,
          ).toInt.U
          io.sbTrainIO.msgReq.bits.msgTypeHasData := false.B

          // Compare results. TODO: after patterns
          val rtrk_l_res = 0.U(1.W)
          val rckn_l_res = 0.U(1.W)
          val rckp_l_res = 0.U(1.W)

          // Fill in msg fields
          io.sbMsgHeaderIO.opCode := Opcode.MessageWithoutData
          io.sbMsgHeaderIO.srcid := SourceID.PhysicalLayer
          io.sbMsgHeaderIO.msgCode := MsgCode.MbInitResp
          io.sbMsgHeaderIO.msgInfo := Cat(0.U(12.W), 1.U(1.W), rtrk_l_res, rckn_l_res, rckp_l_res)
          io.sbMsgHeaderIO.msgSubCode := MbInitMsgSubCode.MbInitRepClkRes

          when(io.sbTrainIO.msgReq.fire) {
            repairClkSubState := RepairClkSubState.WAIT_RESULT_RESP
          }
        }
        is(RepairClkSubState.WAIT_RESULT_RESP) {
          io.sbTrainIO.msgReq.valid := false.B
          io.sbTrainIO.msgReqStatus.ready := true.B
          reqMsgInfo := io.sbTrainIO.msgReqStatus.bits.msgInfo
          when(io.sbTrainIO.msgReqStatus.fire) {
            when(
              io.sbTrainIO.msgReqStatus.bits.status === MessageRequestStatusType.ERR ||
              reqMsgInfo(2, 0).asUInt =/= 7.U(3.W) //TODO: as UInt?
              // when pattern not detected correctly
            ) {
              nextState := State.ERR
            }.otherwise {
              repairClkSubState := RepairClkSubState.SEND_DONE_REQ
            }
          }
        }
        is(RepairClkSubState.SEND_DONE_REQ) {
          // Fill in request fields
          io.sbTrainIO.msgReq.valid := true.B
          io.sbTrainIO.msgReq.bits.reqType := MessageRequestType.MSG_REQ
          io.sbTrainIO.msgReq.bits.timeoutCycles := (
            0.008 * sbClockFreq,
          ).toInt.U
          io.sbTrainIO.msgReq.bits.msgTypeHasData := false.B

          // Fill in msg fields
          io.sbMsgHeaderIO.opCode := Opcode.MessageWithoutData
          io.sbMsgHeaderIO.srcid := SourceID.PhysicalLayer
          io.sbMsgHeaderIO.msgCode := MsgCode.MbInitReq
          io.sbMsgHeaderIO.msgInfo := MsgInfo.RegularResponse
          io.sbMsgHeaderIO.msgSubCode := MbInitMsgSubCode.MbInitRepClkDone

          when(io.sbTrainIO.msgReq.fire) {
            repairClkSubState := RepairClkSubState.WAIT_DONE_REQ
          }
        }
        is(RepairClkSubState.WAIT_DONE_REQ) {
          io.sbTrainIO.msgReq.valid := false.B
          io.sbTrainIO.msgReqStatus.ready := true.B
          when(io.sbTrainIO.msgReqStatus.fire) {
            when(
              io.sbTrainIO.msgReqStatus.bits.status === MessageRequestStatusType.ERR,
            ) {
              nextState := State.ERR
            }.otherwise {
              repairClkSubState := RepairClkSubState.SEND_DONE_RESP
            }
          }
        }
        is(RepairClkSubState.SEND_DONE_RESP) {
          // Fill in request fields
          io.sbTrainIO.msgReq.valid := true.B
          io.sbTrainIO.msgReq.bits.reqType := MessageRequestType.MSG_REQ
          io.sbTrainIO.msgReq.bits.timeoutCycles := (
            0.008 * sbClockFreq,
          ).toInt.U
          io.sbTrainIO.msgReq.bits.msgTypeHasData := false.B

          // Fill in msg fields
          io.sbMsgHeaderIO.opCode := Opcode.MessageWithoutData
          io.sbMsgHeaderIO.srcid := SourceID.PhysicalLayer
          io.sbMsgHeaderIO.msgCode := MsgCode.MbInitResp
          io.sbMsgHeaderIO.msgInfo := MsgInfo.RegularResponse
          io.sbMsgHeaderIO.msgSubCode := MbInitMsgSubCode.MbInitRepClkDone

          when(io.sbTrainIO.msgReq.fire) {
            repairClkSubState := RepairClkSubState.WAIT_DONE_REQ
          }
        }
        is(RepairClkSubState.WAIT_DONE_RESP) {
          io.sbTrainIO.msgReq.valid := false.B
          io.sbTrainIO.msgReqStatus.ready := true.B
          when(io.sbTrainIO.msgReqStatus.fire) {
            when(
              io.sbTrainIO.msgReqStatus.bits.status === MessageRequestStatusType.ERR,
            ) {
              nextState := State.ERR
            }.otherwise {
              nextState := State.REPAIR_VAL
            }
          }
        }
      }
      
    }
    is(State.REPAIR_VAL) {}

  }

}
