// package edu.berkeley.cs.ucie.digital

// import chisel3._
// import chiseltest._
// import org.scalatest.funspec.AnyFunSpec
// import interfaces._

// class sender () extends Module{
//     val io = IO(new Bundle{
//         val bits = Input(Bits(32.W))
//         val valid = Input(Bool())
//         val out = Decoupled3(Bits(32.W))
//     })
//     // io.out.valid := true.B
//     // io.out.irdy := !io.out.ready
//     io.out.valid := io.valid
//     io.out.irdy := io.out.ready
//     io.out.bits := io.bits
// }

// class receiver () extends Module{
//     val io = IO(new Bundle{
//         val ready = Input(Bool())
//         val in = Flipped(Decoupled3(Bits(32.W)))
//         val bits = Output(Bits(32.W))
//     })
//     io.in.ready := io.ready
//     printf(p"${io.in}\n")
//     io.bits := io.in.bits
// }

// class dut () extends Module{
//     val io = IO(new Bundle{
//       val sender_valid = Input(Bool())
//       val sender_data = Input(Bits(32.W))
//       val receiver_ready = Input(Bool())
//       val receiver_data = Output(Bits(32.W))
//     })
//     val sender = Module(new sender())
//     val receiver = Module(new receiver())
//     sender.io.valid := io.sender_valid
//     sender.io.bits := io.sender_data
//     receiver.io.ready := io.receiver_ready
//     sender.io.out <> receiver.io.in
//     io.receiver_data := receiver.io.bits
// }



// class sender2 () extends Module{
//     val io = IO(new Bundle{
//         val enable = Input(Bool())
//         val out = Decoupled3(Bits(32.W))
//     })
//     val readyReg = RegNext(io.out.ready, false.B)
//     io.out.irdy := readyReg
//     io.out.valid := false.B
//     io.out.bits := 0.U
//     // io.out.irdy := !io.out.ready
//     val counter = RegInit(0.U(32.W))
//     counter := counter + 1.U
//     when(io.enable){
//       io.out.enq(counter)
//     }.otherwise{
//       io.out.noenq()
//     }
// }

// class receiver2 () extends Module{
//     val io = IO(new Bundle{
//         val in = Flipped(Decoupled3(Bits(32.W)))
//         val ready = Input(Bool())
//         val out = Output(Bits(32.W))
//     })
//     val regReady = RegNext(io.ready, false.B)
//     io.in.ready := regReady
//     when(io.in.fire){
//       io.out := io.in.deq()
//     }.otherwise{
//       io.out := 0.U
//       io.in.nodeq()
//     }
//     printf(p"${io.in}\n")
// }


// class dut2 () extends Module{
//     val io = IO(new Bundle{
//       val enable = Input(Bool())
//       val ready = Input(Bool())
//       val data = Output(Bits(32.W))
//     })

//     val sender2 = Module(new sender2())
//     val receiver2 = Module(new receiver2())

//     receiver2.io.ready := io.ready
//     sender2.io.out <> receiver2.io.in
//     sender2.io.enable := io.enable
//     io.data := receiver2.io.out
// }



// // class DummyModuleTest extends AnyFunSpec with ChiselScalatestTester {
// //   describe("DummyModule") {
// //     it("should invert its input") {
// //       test(new dut()) { c =>
// //         c.
// //         c.clock.step(1)
// //         c.receiver.io.in.ready.poke(1.U)  
// //         c.clock.step(1)
// //       }
// //     }
// //   }
// // }

// class DummyModuleTest extends AnyFunSpec with ChiselScalatestTester {
//   describe("DUT Module with Decoupled3 Interface") {
//     it("should properly transfer data from sender to receiver when ready and valid") {
//       test(new dut2()) { c =>
//         // Initialize inputs
//         c.io.enable.poke(true.B)  // Example data to send
//         c.io.ready.poke(false.B)
//         // Step the clock to observe the transfer
//         c.clock.step(1)
//         c.io.ready.poke(true.B)
//         c.io.data.peek()
//         c.clock.step(1)
//         c.io.enable.poke(false.B)
//         c.clock.step(1)
//         c.io.data.peek()

//       }
//     }
//   }
// }