# Tiny Whisper-shaped ONNX graphs for the offline onnx-bytedeco contract.
import sys, json, numpy as np, onnx
from onnx import helper as h, TensorProto as T, numpy_helper as nh
out = sys.argv[1]; script = json.loads(sys.argv[2]); vocab = int(sys.argv[3]); prompt = int(sys.argv[4])
# encoder: last_hidden_state = ReduceMean(input_features, axis=2, keepdims) -> [1,80,1]
enc = h.make_graph([h.make_node("ReduceMean", ["input_features", "axes"], ["last_hidden_state"], keepdims=1)],
                   "enc", [h.make_tensor_value_info("input_features", T.FLOAT, [1, 80, 3000])],
                   [h.make_tensor_value_info("last_hidden_state", T.FLOAT, [1, 80, 1])],
                   [nh.from_array(np.array([2], dtype=np.int64), "axes")])
onnx.save(h.make_model(enc, opset_imports=[h.make_opsetid("", 18)], ir_version=8), out + "/encoder.onnx")
# decoder: step = len(input_ids) - prompt; logits[...,-1,:] = one_hot(script[step]) (the
# hidden state is folded in with weight 0 so the input is consumed).
table = np.full((len(script) + 1, vocab), -10.0, dtype=np.float32)
for i, tok in enumerate(script): table[i, tok] = 10.0
table[len(script), script[-1]] = 10.0
nodes = [
  h.make_node("Shape", ["input_ids"], ["shp"]),
  h.make_node("Gather", ["shp", "one"], ["len"], axis=0),
  h.make_node("Sub", ["len", "prompt"], ["step0"]),
  h.make_node("Min", ["step0", "maxstep"], ["step"]),
  h.make_node("Gather", ["table", "step"], ["row"], axis=0),
  h.make_node("ReduceSum", ["encoder_hidden_states"], ["hs"], keepdims=0),
  h.make_node("Mul", ["hs", "zero"], ["hs0"]),
  h.make_node("Add", ["row", "hs0"], ["row2"]),
  h.make_node("Reshape", ["row2", "outshape"], ["logits"]),
]
inits = [nh.from_array(np.array(1, dtype=np.int64), "one"),
         nh.from_array(np.array(prompt, dtype=np.int64), "prompt"),
         nh.from_array(np.array(len(script), dtype=np.int64), "maxstep"),
         nh.from_array(table, "table"),
         nh.from_array(np.array(0.0, dtype=np.float32), "zero"),
         nh.from_array(np.array([1, 1, vocab], dtype=np.int64), "outshape")]
dec = h.make_graph(nodes, "dec",
                   [h.make_tensor_value_info("input_ids", T.INT64, [1, "n"]),
                    h.make_tensor_value_info("encoder_hidden_states", T.FLOAT, [1, 80, 1])],
                   [h.make_tensor_value_info("logits", T.FLOAT, [1, 1, vocab])], inits)
onnx.save(h.make_model(dec, opset_imports=[h.make_opsetid("", 18)], ir_version=8), out + "/decoder.onnx")
