package edu.berkeley.cs.ucie.digital
package logphy

import interfaces._
import sideband.{SBM, SBMessage_factory}

import chisel3._
import chisel3.util._

case class LinkTrainingParams(
    /** The amount of cycles to wait after driving the PLL frequency */
    pllWaitTime: Int = 100,
    maxSBMessageSize: Int = 128,
    mbTrainingParams: MBTrainingParams,
    sbClockFreqAnalog: 800_000_000,
)

class LinkTrainingRdiIO(
    rdiParams: RdiParams,
) extends Bundle {
  val lpStateReq = Input(PhyStateReq())
}

class LinkTrainingFSM(
    linkTrainingParams: LinkTrainingParams,
    afeParams: AfeParams,
    rdiParams: RdiParams,
) extends Module {

  val sbClockFreq = // is 800MHz always
    linkTrainingParams.sbClockFreqAnalog / afeParams.sbSerializerRatio

  val io = IO(new Bundle {
    val mbAfe = new MainbandAfeIo(afeParams)
    val sbAfe = new SidebandAfeIo(afeParams)


    val rdiInitReq = Input(Bool())
    val active = Output(Bool())
  })

  // Method for looking up msg to msgcodes
  def messageIsEqual(rxmsg: UInt, op: Opcode, sub: MsgSubCode, code: MsgCode): Bool = {

        /** opcode */
        (rxmsg(4, 0) === Opcode) &&
        /** subcode */
        (rxmsg(21, 14) === sub) &&
        /** code */
        (rxmsg(39, 32) === code)
      }

  // Pattern generator connection Begin ---------------------------------------------------
  val patternGenerator = new PatternGenerator(afeParams)
  val sbMsgWrapper = new SBMsgWrapper(afeParams) //??????3.9

  private object MsgSource extends ChiselEnum {
    val PATTERN_GENERATOR, SB_MSG_WRAPPER = Value
  }
  private val msgSource = Wire(MsgSource.PATTERN_GENERATOR)

  // io.mainbandLaneIO <> patternGenerator.io.mainbandLaneIO

  when(msgSource === MsgSource.PATTERN_GENERATOR) {
    // io.mbAfe.txData <> patternGenerator.io.mbAfe.txData
    // io.mbAfe.rxData <> patternGenerator.io.mbAfe.rxData
    // io.sbAfe.txData <> patternGenerator.io.sbAfe.txData
    // io.sbAfe.rxData <> patternGenerator.io.sbAfe.rxData
    // io.mbAfe <> patternGenerator.io.mbAfe
    io.sbAfe <> patternGenerator.io.sbAfe
  } elsewhen (msgSource === MsgSource.SB_MSG_WRAPPER) {
    io.sbAfe <> sbMsgWrapper.io.sbAfe
  }
  // Pattern generator connection End -----------------------------------------------------

  private val currentState = RegInit(LinkTrainingState.reset)
  private val nextState = Wire(currentState)

  // As per spec p84, Physical Layer must stay in RESET for a minimum of 4ms upon every entry to RESET
  private val resetCounter = Counter(
    Range(1, 8000000), // Currently link rate is by default 4GT/s, so mainband clock is 2GHz. 4ms = 8M cycles. parametrization?
    true.B,
    (nextState === LinkTrainingState.reset && currentState =/= LinkTrainingState.reset), // TODO: does this also reset on implicit reset
  )
  io.mbAfe.txZpd := VecInit.fill(afeParams.mbLanes)(0.U)
  io.mbAfe.txZpu := VecInit.fill(afeParams.mbLanes)(0.U)
  io.mbAfe.rxZ := VecInit.fill(afeParams.mbLanes)(0.U)

  // Reset sub-state init Begin --------------------------------------------------------
  private val resetSubState = RegInit(ResetSubState.INIT)
  when(nextState === LinkTrainingState.reset) { //TODO: incorporate lp reset information into nextstate
    resetSubState := ResetSubState.INIT
  }
  // Reset sub-state init End ----------------------------------------------------------

  // SbInit sub-state init Begin -------------------------------------------------------
  private val sbInitSubState = RegInit(SBInitSubState.SEND_CLOCK)
  when(
    nextState === LinkTrainingState.sbInit && currentState =/= LinkTrainingState.sbInit,
  ) {
    sbInitSubState := SBInitSubState.SEND_CLOCK
  }
  // SbInit sub-state init End ---------------------------------------------------------

  // MbInit sub-state init Begin -------------------------------------------------------
  private val mbInit = Module(
    new MBInitFSM(
      linkTrainingParams,
      linkTrainingParams.mbTrainingParams,
      afeParams,
    ),
  )
  mbInit.reset := (nextState === LinkTrainingState.mbInit) && (currentState =/= LinkTrainingState.mbInit)
  // MbInit sub-state init End ---------------------------------------------------------

  when(io.rdiInitReq === true.B) {
    currentState := LinkTrainingState.reset
  }.otherwise {
    currentState := nextState
  }
  io.active := currentState === LinkTrainingState.active

  switch(currentState) {
    is(LinkTrainingState.reset) {
      io.mbAfe.rxEn := false.B
      io.sbAfe.rxEn := true.B
      val resetFreqCtrValue = false.B
      io.mbAfe.txZpd := VecInit(Seq.fill(afeParams.mbLanes)(0.U))
      io.mbAfe.txZpu := VecInit(Seq.fill(afeParams.mbLanes)(0.U))
      val (freqSelCtrValue, freqSelCtrWrap) = Counter( //? what is this counter
        Range(1, linkTrainingParams.pllWaitTime),
        true.B,
        resetFreqCtrValue,
      )
      switch(resetSubState) {
        is(ResetSubState.INIT) {
          when(io.mbAfe.pllLock && io.sbAfe.pllLock) { //? aren't plllocks output?
            io.mbAfe.txFreqSel := SpeedMode.speed4
            resetSubState := ResetSubState.FREQ_SEL_CYC_WAIT
            resetFreqCtrValue := true.B
          }
        }
        is(ResetSubState.FREQ_SEL_CYC_WAIT) {
          when(freqSelCtrValue === (linkTrainingParams.pllWaitTime - 1).U) {
            resetSubState := ResetSubState.FREQ_SEL_LOCK_WAIT
          }
        }
        is(ResetSubState.FREQ_SEL_LOCK_WAIT) {
          when(
            io.mbAfe.pllLock && io.sbAfe.pllLock && (io.rdi.lpStateReq =/= PhyStateReq.linkReset //?
            /** TODO: what happened to reset */
            ),
          ) {
            nextState := LinkTrainingState.sbInit
          }
        }

      }
    }

    // SbInit sub-state Begin ------------------------------------------------------------
    is(LinkTrainingState.sbInit) {

      switch(sbInitSubState) {
        is(SBInitSubState.SEND_CLOCK) {
          patternGenerator.io.patternGeneratorIO.transmitInfo.bits.pattern := TransmitPattern.CLOCK_64_LOW_32
          patternGenerator.io.patternGeneratorIO.transmitInfo.bits.sideband := true.B

          /** Timeout occurs after 8ms */
          patternGenerator.io.patternGeneratorIO.transmitInfo.bits.timeoutCycles := (
            0.008 * sbClockFreq, // 0.008*freq=cycles
          ).toInt.U

          patternGenerator.io.patternGeneratorIO.transmitInfo.valid := true.B
          msgSource := MsgSource.PATTERN_GENERATOR

          // When the pattern generator accepts the request (i.e. not busy), move on
          when(patternGenerator.io.patternGeneratorIO.transmitInfo.fire) {
            sbInitSubState := SBInitSubState.WAIT_CLOCK
          }
        }
        is(SBInitSubState.WAIT_CLOCK) {
          patternGenerator.io.patternGeneratorIO.transmitPatternStatus.ready := true.B
          msgSource := MsgSource.PATTERN_GENERATOR
          when(
            patternGenerator.io.patternGeneratorIO.transmitPatternStatus.fire,
          ) {
            switch(
              patternGenerator.io.patternGeneratorIO.transmitPatternStatus.bits,
            ) {
              is(MessageRequestStatusType.SUCCESS) {
                sbInitSubState := SBInitSubState.SB_OUT_OF_RESET_EXCH
              }
              is(MessageRequestStatusType.ERR) {
                nextState := LinkTrainingState.linkError
              }
            }
          }
        }
        is(SBInitSubState.SB_OUT_OF_RESET_EXCH) {
          // sbMsgWrapper.io.trainIO.msgReq.bits.msg := SBMessage_factory(
          //   SBM.SBINIT_OUT_OF_RESET,
          //   "PHY",
          //   true,
          //   "PHY",
          // )

          //TODO: should create a way to wrap around all the fields in the packet
          //e.g., look up table converting a msg to the required fields
          //see sb-msg-encodings.scala
          sbMsgWrapper.io.msgHeaderIO.opCode := Opcode.MessageWithoutData
          sbMsgWrapper.io.msgHeaderIO.srcid := SourceID.PhysicalLayer
          sbMsgWrapper.io.msgHeaderIO.msgCode := MsgCode.SbOutofReset
          sbMsgWrapper.io.msgHeaderIO.msgInfo := 0.U //TODO: result[3:0]?
          sbMsgWrapper.io.msgHeaderIO.msgSubCode := MsgSubCode.Crd

          sbMsgWrapper.io.trainIO.msgReq.bits.reqType := MessageRequestType.MSG_EXCH
          sbMsgWrapper.io.trainIO.msgReq.bits.msgTypeHasData := false.B
          sbMsgWrapper.io.trainIO.msgReq.valid := true.B

          sbMsgWrapper.io.trainIO.msgReq.bits.timeoutCycles := (
            0.008 * sbClockFreq,
          ).toInt.U
          msgSource := MsgSource.SB_MSG_WRAPPER

          // When sb msg wrapper accepts the request (i.e. not busy), move on
          when(sbMsgWrapper.io.trainIO.msgReq.fire) {
            sbInitSubState := SBInitSubState.SB_OUT_OF_RESET_WAIT
          }
        }
        is(SBInitSubState.SB_OUT_OF_RESET_WAIT) {
          sbMsgWrapper.io.trainIO.msgReqStatus.ready := true.B
          msgSource := MsgSource.SB_MSG_WRAPPER
          when(sbMsgWrapper.io.trainIO.msgReqStatus.fire) {
            switch(sbMsgWrapper.io.trainIO.msgReqStatus.bits.status) {
              is(MessageRequestStatusType.SUCCESS) {
                sbInitSubState := SBInitSubState.SB_DONE_REQ
              }
              is(MessageRequestStatusType.ERR) {
                nextState := LinkTrainingState.linkError
              }
            }
          }
        }
        is(SBInitSubState.SB_DONE_REQ) {
          // sbMsgWrapper.io.trainIO.msgReq.bits.msg := SBMessage_factory(
          //   SBM.SBINIT_DONE_REQ,
          //   "PHY",
          //   false,
          //   "PHY",
          // )
          sbMsgWrapper.io.msgHeaderIO.opCode := Opcode.MessageWithoutData
          sbMsgWrapper.io.msgHeaderIO.srcid := SourceID.PhysicalLayer
          sbMsgWrapper.io.msgHeaderIO.msgCode := MsgCode.SbInitReq
          sbMsgWrapper.io.msgHeaderIO.msgInfo := MsgInfo.RegularResponse
          sbMsgWrapper.io.msgHeaderIO.msgSubCode := MsgSubCode.Active

          sbMsgWrapper.io.trainIO.msgReq.bits.reqType := MessageRequestType.MSG_REQ
          sbMsgWrapper.io.trainIO.msgReq.bits.msgTypeHasData := false.B
          sbMsgWrapper.io.trainIO.msgReq.valid := true.B
          sbMsgWrapper.io.trainIO.msgReq.bits.timeoutCycles := (
            0.008 * sbClockFreq,
          ).toInt.U
          msgSource := MsgSource.SB_MSG_WRAPPER
          when(sbMsgWrapper.io.trainIO.msgReq.fire) {
            sbInitSubState := SBInitSubState.SB_DONE_REQ_WAIT
          }
        }
        is(SBInitSubState.SB_DONE_REQ_WAIT) {
          sbMsgWrapper.io.trainIO.msgReqStatus.ready := true.B
          msgSource := MsgSource.SB_MSG_WRAPPER
          when(sbMsgWrapper.io.trainIO.msgReqStatus.fire) {
            switch(sbMsgWrapper.io.trainIO.msgReqStatus.bits.status) {
              is(MessageRequestStatusType.SUCCESS) {
                sbInitSubState := SBInitSubState.SB_DONE_RESP
              }
              is(MessageRequestStatusType.ERR) {
                nextState := LinkTrainingState.linkError
              }
            }
          }
        }
        is(SBInitSubState.SB_DONE_RESP) {
          // sbMsgWrapper.io.trainIO.msgReq.bits.msg := SBMessage_factory(
          //   SBM.SBINIT_DONE_RESP,
          //   "PHY",
          //   false,
          //   "PHY",
          // )
          sbMsgWrapper.io.msgHeaderIO.opCode := Opcode.MessageWithoutData
          sbMsgWrapper.io.msgHeaderIO.srcid := SourceID.PhysicalLayer
          sbMsgWrapper.io.msgHeaderIO.msgCode := MsgCode.SbInitResp
          sbMsgWrapper.io.msgHeaderIO.msgInfo := MsgInfo.RegularResponse
          sbMsgWrapper.io.msgHeaderIO.msgSubCode := MsgSubCode.Active

          sbMsgWrapper.io.trainIO.msgReq.bits.reqType := MessageRequestType.MSG_RESP
          sbMsgWrapper.io.trainIO.msgReq.valid := true.B
          sbMsgWrapper.io.trainIO.msgReq.bits.msgTypeHasData := false.B
          msgSource := MsgSource.SB_MSG_WRAPPER
          sbMsgWrapper.io.trainIO.msgReq.bits.timeoutCycles := (
            0.008 * sbClockFreq,
          ).toInt.U
          when(sbMsgWrapper.io.trainIO.msgReq.fire) {
            sbInitSubState := SBInitSubState.SB_DONE_RESP_WAIT
          }
        }
        is(SBInitSubState.SB_DONE_RESP_WAIT) {
          sbMsgWrapper.io.trainIO.msgReqStatus.ready := true.B
          msgSource := MsgSource.SB_MSG_WRAPPER
          when(sbMsgWrapper.io.trainIO.msgReqStatus.fire) {
            switch(sbMsgWrapper.io.trainIO.msgReqStatus.bits.status) {
              is(MessageRequestStatusType.SUCCESS) {
                nextState := LinkTrainingState.mbInit
              }
              is(MessageRequestStatusType.ERR) {
                nextState := LinkTrainingState.linkError
              }
            }
          }
        }
      }
    }
    // SbInit sub-state End --------------------------------------------------------------

    is(LinkTrainingState.mbInit) {
      mbInit.io.sbTrainIO <> sbMsgWrapper.io.trainIO
      msgSource := MsgSource.SB_MSG_WRAPPER
      when(mbInit.io.transition.asBool) {
        nextState := Mux(
          mbInit.io.error,
          LinkTrainingState.linkError,
          LinkTrainingState.linkInit,
        )
      }
    }
    is(LinkTrainingState.linkInit) {}
    is(LinkTrainingState.active) {

      /** Active state = do nothing, not currently in training.
        */
    }
    is(LinkTrainingState.linkError) {
      // TODO: What to do when I receive an error?
    }
  }

}
