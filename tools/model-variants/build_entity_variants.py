"""Build the entity-model variant asset set.

All variants reuse the ORIGINAL weights byte-for-byte through one shared ONNX external-data file;
only the shape constants and the anchor/stride tables baked for the 640x640 canvas are regenerated.
Output directory layout (shipped under assets/pyla/models/entity_variants/):
  entity_weights.bin, entity_<W>x<H>.onnx ..., manifest.json
"""
import hashlib, json, os, sys
import numpy as np
import onnx
from onnx import numpy_helper as nh
from onnx.external_data_helper import convert_model_to_external_data

HERE = os.path.dirname(os.path.abspath(__file__))
SRC = os.path.join(HERE, '..', '..', 'app', 'src', 'main', 'assets', 'pyla', 'models', 'mainInGameModel.onnx')
OUT = sys.argv[1] if len(sys.argv) > 1 else os.path.join(HERE, 'entity_variants')
WEIGHTS = 'entity_weights.bin'
VARIANTS = [(640, 384), (512, 288), (448, 256)]  # (W, H)
ANCHOR_NAMES = ('/model.23/Constant_9_output_0', '/model.23/Constant_10_output_0')
STRIDE_NAME = '/model.23/Constant_12_output_0'


def sha(path):
    h = hashlib.sha256()
    with open(path, 'rb') as f:
        for chunk in iter(lambda: f.read(1 << 20), b''):
            h.update(chunk)
    return h.hexdigest()


def anchors(levels):
    pts, st = [], []
    for h, w, s in levels:
        sx = np.arange(w, dtype=np.float32) + 0.5
        sy = np.arange(h, dtype=np.float32) + 0.5
        yy, xx = np.meshgrid(sy, sx, indexing='ij')
        pts.append(np.stack([xx.ravel(), yy.ravel()], 0))
        st.append(np.full(h * w, s, np.float32))
    return np.concatenate(pts, 1)[None], np.concatenate(st)[None]


def main():
    os.makedirs(OUT, exist_ok=True)
    full = onnx.load(SRC)
    init = {i.name: i for i in full.graph.initializer}
    # Prove the exporter's anchor layout on the original canvas before deriving anything.
    a0, s0 = anchors([(80, 80, 8), (40, 40, 16), (20, 20, 32)])
    for name in ANCHOR_NAMES:
        assert np.array_equal(nh.to_array(init[name]), a0), name
    assert np.array_equal(nh.to_array(init[STRIDE_NAME]), s0)
    assert nh.to_array(init['/model.10/m/m.0/attn/Constant_output_0']).tolist() == [1, 2, 128, 400]
    assert nh.to_array(init['/model.10/m/m.0/attn/Constant_3_output_0']).tolist() == [1, 128, 20, 20]
    assert nh.to_array(init['/model.23/dfl/Constant_output_0']).tolist() == [1, 4, 16, 8400]
    assert nh.to_array(init['/model.23/dfl/Constant_1_output_0']).tolist() == [1, 4, 8400]

    # Externalise only real weights; keep every tensor we may patch inline.
    patched = set(ANCHOR_NAMES) | {STRIDE_NAME}
    keep_inline = {}
    for name in patched:
        keep_inline[name] = nh.to_array(init[name]).copy()
    base_path = os.path.join(OUT, '_base.onnx')
    weights_path = os.path.join(OUT, WEIGHTS)
    if os.path.exists(weights_path):
        os.remove(weights_path)
    convert_model_to_external_data(full, all_tensors_to_one_file=True, location=WEIGHTS, size_threshold=4096)
    onnx.save(full, base_path)
    weights_sha = sha(weights_path)

    manifest = {'schema': 1, 'source_sha256': sha(SRC), 'weights': WEIGHTS,
                'weights_sha256': weights_sha, 'weights_bytes': os.path.getsize(weights_path), 'variants': []}
    for W, H in VARIANTS:
        m = onnx.load(base_path, load_external_data=False)
        g = m.graph
        by = {i.name: i for i in g.initializer}
        levels = [(H // s, W // s, s) for s in (8, 16, 32)]
        n = sum(h * w for h, w, _ in levels)
        h32, w32, _ = levels[2]

        def put(name, arr):
            by[name].CopyFrom(nh.from_array(np.asarray(arr), name))

        put('/model.10/m/m.0/attn/Constant_output_0', np.array([1, 2, 128, h32 * w32], np.int64))
        put('/model.10/m/m.0/attn/Constant_3_output_0', np.array([1, 128, h32, w32], np.int64))
        put('/model.23/dfl/Constant_output_0', np.array([1, 4, 16, n], np.int64))
        put('/model.23/dfl/Constant_1_output_0', np.array([1, 4, n], np.int64))
        a, s = anchors(levels)
        for name in ANCHOR_NAMES:
            put(name, a)
        put(STRIDE_NAME, s)
        d = g.input[0].type.tensor_type.shape.dim
        d[2].dim_value = H
        d[3].dim_value = W
        g.output[0].type.tensor_type.shape.dim[2].dim_value = n
        del g.value_info[:]
        name = f'entity_{W}x{H}.onnx'
        path = os.path.join(OUT, name)
        onnx.save(m, path)  # external references are preserved untouched
        assert sha(weights_path) == weights_sha, 'weights file must never be rewritten'
        onnx.checker.check_model(path)
        manifest['variants'].append({'file': name, 'width': W, 'height': H, 'anchors': n,
                                     'sha256': sha(path), 'bytes': os.path.getsize(path)})
    os.remove(base_path)
    with open(os.path.join(OUT, 'manifest.json'), 'w') as f:
        json.dump(manifest, f, indent=1)
    print(json.dumps(manifest, indent=1))


if __name__ == '__main__':
    main()
