package edu.berkeley.cs.ucie.digital
package sideband

import chisel3._

object LinkTrainingMsgSubCode extends ChiselEnum {
  val
  StartTxInitiatedD2CPointTest, 
  lfsrClearErrorReq             = Value
  // TODO: Define more here p.148
}
