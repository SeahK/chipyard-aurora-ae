
package chipyard.rerocc

import chisel3._
import chisel3.util._
import freechips.rocketchip.config.Parameters
import freechips.rocketchip.diplomacy._
import freechips.rocketchip.tilelink._

class TLThrottlerReq(param_bitwidth: Int) extends Bundle {
  val epoch = UInt(param_bitwidth.W)
  val max_req = UInt(param_bitwidth.W)
}

class TLThrottler(param_bitwidth: Int)(implicit p: Parameters) extends LazyModule {
  val node = TLIdentityNode()
  override def shouldBeInlined = false
  override lazy val module = new Impl
  class Impl extends LazyModuleImp(this) {
    //lazy val module = new LazyModuleImp(this) {
    val io = IO(new Bundle {
      val req = Flipped(Decoupled(new TLThrottlerReq(param_bitwidth)))
      val prev_reqs = UInt(param_bitwidth.W)
    })

    def satAdd(u: UInt, v: UInt, max: UInt): UInt = {
      Mux(u +& v > max, max, u + v)
    }

    def floorAdd(u: UInt, n: UInt, max_plus_one: UInt, en: Bool = true.B): UInt = {
      val max = max_plus_one - 1.U
      MuxCase(u + n, Seq(
        (!en) -> u,
        ((u +& n) > max) -> 0.U
      ))
    }
    val req_counter = RegInit(0.U((param_bitwidth).W))
    //val offset = RegInit(0.U(param_bitwidth.W))
    val epoch = RegInit(0.U((param_bitwidth).W))
    val max_req = RegInit(0.U((param_bitwidth).W))
    val epoch_temp = RegInit(0.U((param_bitwidth).W))
    val max_req_temp = RegInit(0.U((param_bitwidth).W))
    val prev_req = RegInit(0.U(param_bitwidth.W))
    dontTouch(max_req)
    dontTouch(max_req_temp)

    io.prev_reqs := prev_req
    val temp_waiting = RegInit(false.B)
    io.req.ready := true.B//!temp_waiting

    val epoch_counter = RegInit(0.U((param_bitwidth).W))
    when(io.req.fire){
      epoch_temp := io.req.bits.epoch
      max_req_temp := io.req.bits.max_req
      temp_waiting := true.B
      when(epoch_counter === epoch - 1.U || epoch === 0.U){
        epoch := io.req.bits.epoch
        max_req := io.req.bits.max_req
        temp_waiting := false.B
      }
    }
    epoch_counter := floorAdd(epoch_counter, 1.U, epoch, epoch > 0.U) // ToDo: initialize epoch when released & allocated

    (node.in zip node.out) foreach { case ((in, edgeIn), (out, edgeOut)) =>
      //val throttle_queue = Queue(in.a)
      val throttle_queue = Module(new Queue(new TLBundleA(edgeIn.bundle), 1))
      throttle_queue.io.enq <> in.a
      when(out.a.fire && epoch > 0.U){
        req_counter := req_counter + (((1.U << out.a.bits.size) >> 4.U).asUInt)
      }
      //out.a <> in.a // Add throttle to this, in.a is Decoupled[TLBundleA]
      out.a <> throttle_queue.io.deq
      //out.a.bits := throttle_queue.io.deq.bits
      when(req_counter > (max_req) && epoch =/= 0.U && max_req =/= 0.U){
        //throttle_queue.io.enq.valid := false.B
        throttle_queue.io.deq.ready := false.B
        out.a.valid := false.B
        //offset := 0.U // clear out offset
      }
      //out.a.ready := Mux((req_counter < max_req || epoch === 0.U || max_req === 0.U), throttle_queue.io.deq.valid, false.B)

      in.d <> out.d

      if (edgeOut.manager.anySupportAcquireB && edgeOut.client.anySupportProbe) {
        in .b <> out.b
        out.c <> in .c
        out.e <> in .e
      } else {
        in.b.valid := false.B
        in.c.ready := true.B
        in.e.ready := true.B
        out.b.ready := true.B
        out.c.valid := false.B
        out.e.valid := false.B
      }
    }

    when(epoch === 0.U && !io.req.fire){
      epoch_counter := 0.U
      req_counter := 0.U
      temp_waiting := false.B
    //offset := 0.U
    }.elsewhen(epoch_counter === epoch - 1.U) {
      when (req_counter =/= 0.U){
        prev_req := req_counter
        
      }
      req_counter := 0.U
      when(temp_waiting) {
        epoch := epoch_temp
        max_req := max_req_temp
        temp_waiting := false.B
        //offset := 0.U
      }
    }
  }
}
