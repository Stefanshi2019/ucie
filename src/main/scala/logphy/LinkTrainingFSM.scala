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
  // def messageIsEqual(rxmsg: UInt, op: Opcode, sub: MsgSubCode, code: MsgCode): Bool = {

  //       /** opcode */
  //       (rxmsg(4, 0) === Opcode) &&
  //       /** subcode */
  //       (rxmsg(21, 14) === sub) &&
  //       /** code */
  //       (rxmsg(39, 32) === code)
  //     }

  // Pattern generator, sbMsgWrapper connection Begin -------------------------------
  val patternGenerator = new PatternGenerator(afeParams)
  val patternGeneratorMb = new PatternGenerator(afeParams)
  val sbMsgWrapper = new SBMsgWrapper(afeParams)

  private val msgSource = Wire(MsgSource.PATTERN_GENERATOR) //this msg source is for sb

  private object SbMsgWrapperSource extends ChiselEnum {
    val MAIN, MBINITFSM = Value
  }
  private val sbMsgSource = Wire(SbMsgWrapperSource.MAIN)

  private val sbWrapperTrainIO = RegInit(Flipped(SBMsgWrapperTrainIO)) //TODO: init value
  private val sbWrapperMsgHeaderIO = RegInit(Flipped(SBMsgWrapperHeaderIO)) //TODO: init value

  // io.mainbandLaneIO <> patternGenerator.io.mainbandLaneIO

  when(msgSource === MsgSource.PATTERN_GENERATOR) {
    io.sbAfe <> patternGenerator.io.sbAfe
  }.elsewhen (msgSource === MsgSource.SB_MSG_WRAPPER) {
    io.sbAfe <> sbMsgWrapper.io.sbAfe
  }
  
  patternGenerator.io.patternGeneratorIO.sideband := true.B
  io.mbAfe <> patternGeneratorMb.io.mbAfe
  patternGeneratorMb.io.patternGeneratorIO <> mbInit.io.patternGenIo
  patternGeneratorMb.io.patternGeneratorIO.sideband := false.B

when(sbMsgSource === SbMsgWrapperSource.MAIN) {
  sbWrapperTrainIO <> sbMsgWrapper.io.trainIO
  sbWrapperMsgHeaderIO <> sbMsgWrapper.io.msgHeaderIO
}.elsewhen(sbMsgSource === SbMsgWrapperSource.MBINITFSM) {
  mbInit.io.sbTrainIO <> sbMsgWrapper.io.trainIO
  mbInit.io.sbMsgHeaderIO <> sbMsgWrapper.io.msgHeaderIO
  mbInit.io.patternGenIo <> transmitPatternStatus.io.patternGeneratorIO
}

  // Pattern generator, sbMsgWrapper connection End ----------------------------------------

  // States Start ---------------------------------------------------------------------
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

  private val resetSubState = RegInit(ResetSubState.INIT)
  when(nextState === LinkTrainingState.reset) { //TODO: incorporate lp reset information into nextstate
    resetSubState := ResetSubState.INIT
  }

  private val sbInitSubState = RegInit(SBInitSubState.SEND_CLOCK)
  when(
    nextState === LinkTrainingState.sbInit && currentState =/= LinkTrainingState.sbInit,
  ) {
    sbInitSubState := SBInitSubState.SEND_CLOCK
  }

  private val mbInit = Module(
    new MBInitFSM(
      linkTrainingParams,
      linkTrainingParams.mbTrainingParams,
      afeParams,
    ),
  )
  mbInit.reset := (nextState === LinkTrainingState.mbInit) && (currentState =/= LinkTrainingState.mbInit)

  when(io.rdiInitReq === true.B) {
    currentState := LinkTrainingState.reset
  }.otherwise {
    currentState := nextState
  }
  io.active := currentState === LinkTrainingState.active

  // States End ------------------------------------------------------------------

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
      sbMsgSource := SbMsgWrapperSource.MAIN

      switch(sbInitSubState) {
        is(SBInitSubState.SEND_CLOCK) {
          patternGenerator.io.patternGeneratorIO.transmitInfo.bits.pattern := TransmitPattern.CLOCK_64_LOW_32
          // patternGenerator.io.patternGeneratorIO.transmitInfo.bits.sideband := true.B

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
          sbWrapperMsgHeaderIO.opCode := Opcode.MessageWithoutData
          sbWrapperMsgHeaderIO.srcid := SourceID.PhysicalLayer
          sbWrapperMsgHeaderIO.msgCode := MsgCode.SbOutofReset
          sbWrapperMsgHeaderIO.msgInfo := 0.U //TODO: result[3:0]?
          sbWrapperMsgHeaderIO.msgSubCode := MsgSubCode.Crd

          sbWrapperTrainIO.msgReq.bits.reqType := MessageRequestType.MSG_EXCH
          sbWrapperTrainIO.msgReq.bits.msgTypeHasData := false.B
          sbWrapperTrainIO.msgReq.valid := true.B

          sbWrapperTrainIO.msgReq.bits.timeoutCycles := (
            0.008 * sbClockFreq,
          ).toInt.U
          msgSource := MsgSource.SB_MSG_WRAPPER

          // When sb msg wrapper accepts the request (i.e. not busy), move on
          when(sbWrapperTrainIO.msgReq.fire) {
            sbInitSubState := SBInitSubState.SB_OUT_OF_RESET_WAIT
          }
        }
        is(SBInitSubState.SB_OUT_OF_RESET_WAIT) {
          sbWrapperTrainIO.msgReqStatus.ready := true.B
          msgSource := MsgSource.SB_MSG_WRAPPER
          when(sbWrapperTrainIO.msgReqStatus.fire) {
            switch(sbWrapperTrainIO.msgReqStatus.bits.status) {
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
          sbWrapperMsgHeaderIO.opCode := Opcode.MessageWithoutData
          sbWrapperMsgHeaderIO.srcid := SourceID.PhysicalLayer
          sbWrapperMsgHeaderIO.msgCode := MsgCode.SbInitReq
          sbWrapperMsgHeaderIO.msgInfo := MsgInfo.RegularResponse
          sbWrapperMsgHeaderIO.msgSubCode := MsgSubCode.Active

          sbWrapperTrainIO.msgReq.bits.reqType := MessageRequestType.MSG_REQ
          sbWrapperTrainIO.msgReq.bits.msgTypeHasData := false.B
          sbWrapperTrainIO.msgReq.valid := true.B
          sbWrapperTrainIO.msgReq.bits.timeoutCycles := (
            0.008 * sbClockFreq,
          ).toInt.U
          msgSource := MsgSource.SB_MSG_WRAPPER
          when(sbWrapperTrainIO.msgReq.fire) {
            sbInitSubState := SBInitSubState.SB_DONE_REQ_WAIT
          }
        }
        is(SBInitSubState.SB_DONE_REQ_WAIT) {
          sbWrapperTrainIO.msgReqStatus.ready := true.B
          msgSource := MsgSource.SB_MSG_WRAPPER
          when(sbWrapperTrainIO.msgReqStatus.fire) {
            switch(sbWrapperTrainIO.msgReqStatus.bits.status) {
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
          sbWrapperMsgHeaderIO.opCode := Opcode.MessageWithoutData
          sbWrapperMsgHeaderIO.srcid := SourceID.PhysicalLayer
          sbWrapperMsgHeaderIO.msgCode := MsgCode.SbInitResp
          sbWrapperMsgHeaderIO.msgInfo := MsgInfo.RegularResponse
          sbWrapperMsgHeaderIO.msgSubCode := MsgSubCode.Active

          sbWrapperTrainIO.msgReq.bits.reqType := MessageRequestType.MSG_RESP
          sbWrapperTrainIO.msgReq.valid := true.B
          sbWrapperTrainIO.msgReq.bits.msgTypeHasData := false.B
          msgSource := MsgSource.SB_MSG_WRAPPER
          sbWrapperTrainIO.msgReq.bits.timeoutCycles := (
            0.008 * sbClockFreq,
          ).toInt.U
          when(sbWrapperTrainIO.msgReq.fire) {
            sbInitSubState := SBInitSubState.SB_DONE_RESP_WAIT
          }
        }
        is(SBInitSubState.SB_DONE_RESP_WAIT) {
          sbWrapperTrainIO.msgReqStatus.ready := true.B
          msgSource := MsgSource.SB_MSG_WRAPPER
          when(sbWrapperTrainIO.msgReqStatus.fire) {
            switch(sbWrapperTrainIO.msgReqStatus.bits.status) {
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
      mbAfe.txFreqSel := SpeedMode.speed4 //As per p86, mainband data rate is set to lowest supported (4GT/s)

      msgSource := mbInit.io.msgSource
      sbMsgSource := SbMsgWrapperSource.MBINITFSM

      when(mbInit.io.done.asBool) {
        nextState := Mux(
          mbInit.io.error,
          LinkTrainingState.linkError,
          LinkTrainingState.mbTrain,
        )
      }
    }
    is(LinkTrainingState.mbTrain) {
      //NOTE: As mbTrain mainly does calibration of analog characteristics, it is not implemented
      nextState := LinkTrainingState.linkInit
    }
    is(LinkTrainingState.linkInit) {}
    is(LinkTrainingState.active) {

      /** Active state = do nothing, not currently in training; unless upon rdi request, which is handled previously
        */
    }
    is(LinkTrainingState.linkError) {
      // TODO: What to do when I receive an error?
    }
  }

}
