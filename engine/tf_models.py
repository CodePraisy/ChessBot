"""TensorFlow/Keras value networks in five sizes, and the portable export Java plays with.

Every size is the same shape of model: 772 board features -> ReLU hidden layers -> one logit
(win probability for the side to move). Bigger models judge positions better but evaluate more
slowly, so the Java search reaches fewer moves ahead in the same thinking time.
"""
from __future__ import annotations

import json
import os
from pathlib import Path
import tempfile
import time

import numpy as np

from tf_features import FEATURES, SCALE

SIZES = {
    "super-small": [32],
    "small": [128],
    "medium": [256, 32],
    "large": [512, 64],
    "larger": [1024, 256, 32],
}
FORMAT = "chessbot-mlp"
VERSION = 2
MODELS = Path(__file__).resolve().parent / "models" / "tf"


def build(size: str, seed: int = 0, hidden: list[int] | None = None, l2: float = 0.0):
    """A size from SIZES, or any hidden-layer list (the gym varies widths slightly)."""
    import tensorflow as tf
    if hidden is None and size not in SIZES:
        raise ValueError(f"Unknown size {size!r}; choose from {', '.join(SIZES)}")
    tf.keras.utils.set_random_seed(seed)
    inputs = tf.keras.Input(shape=(FEATURES,), name="board")
    h = inputs
    regularizer = tf.keras.regularizers.L2(l2) if l2 > 0 else None
    for i, units in enumerate(hidden or SIZES[size]):
        h = tf.keras.layers.Dense(units, activation="relu", kernel_regularizer=regularizer, name=f"hidden{i}")(h)
    logit = tf.keras.layers.Dense(1, name="logit")(h)
    return tf.keras.Model(inputs, logit, name=f"chessbot_{size.replace('-', '_')}")


def parameter_count(size: str) -> int:
    layers = [FEATURES] + SIZES[size] + [1]
    return sum(a * b + b for a, b in zip(layers, layers[1:]))


def weights_of(model) -> list[np.ndarray]:
    out = []
    for layer in model.layers:
        if layer.weights:
            kernel, bias = layer.get_weights()
            out += [kernel.astype(np.float32), bias.astype(np.float32)]
    return out


def _atomic(path: Path, write):
    with tempfile.NamedTemporaryFile(dir=path.parent, suffix=".tmp", delete=False) as handle:
        temporary = Path(handle.name)
    try:
        write(temporary)
        os.replace(temporary, path)
    finally:
        temporary.unlink(missing_ok=True)


def export(model, folder: Path, info: dict) -> None:
    """Writes weights.npz + model.json (what Java loads). The .keras file stays for further training."""
    folder.mkdir(parents=True, exist_ok=True)
    weights = weights_of(model)
    layers = [FEATURES] + [w.shape[1] for w in weights[0::2]]
    arrays = {}  # w0, b0, w1, b1, ...: Dense kernels are [inputs, outputs]
    for i, (w, b) in enumerate(zip(weights[0::2], weights[1::2])):
        arrays[f"w{i}"], arrays[f"b{i}"] = w, b

    def write_npz(path):
        with open(path, "wb") as handle:
            np.savez(handle, **arrays)
    _atomic(folder / "weights.npz", write_npz)
    meta = dict(info, format=FORMAT, version=VERSION, features="mover772", scale=SCALE,
                activation="relu", layers=layers, parameters=int(sum(w.size for w in weights)),
                exported=time.strftime("%Y-%m-%d %H:%M:%S"))
    _atomic(folder / "model.json", lambda path: path.write_text(json.dumps(meta, indent=2)))


def model_tag(size: str) -> str:
    """A name like small-0000042. One counter (engine/models/tf/counter.json) is shared by every model,
    so each new model gets the next number and no two ever share one."""
    path = MODELS / "counter.json"
    MODELS.mkdir(parents=True, exist_ok=True)
    n = (json.loads(path.read_text()).get("last", 0) if path.exists() else 0) + 1
    _atomic(path, lambda tmp: tmp.write_text(json.dumps({"last": n})))
    return f"{size}-{n:07d}"


def load_exported(folder: Path) -> tuple[list[np.ndarray], dict]:
    meta = json.loads((Path(folder) / "model.json").read_text())
    if meta.get("format") != FORMAT or meta.get("version") != VERSION:
        raise ValueError("Not a ChessBot TensorFlow model export")
    with np.load(Path(folder) / "weights.npz", allow_pickle=False) as data:
        count = len(meta["layers"]) - 1
        weights = [np.array(data[f"{k}{i}"]) for i in range(count) for k in "wb"]
    return weights, meta
