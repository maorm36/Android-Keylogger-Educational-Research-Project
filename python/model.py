"""
MODEL-ONLY sensitive data classifier (no regex at inference time).

This trains a 5-class text classifier based on data txt files and exports a TFLite model:
  0 = normal
  1 = password
  2 = email
  3 = credit_card
  4 = phone_il

IMPORTANT (safety + generalization):
- all digits are normalized to the letter 'D' *during both training and inference*.
  That lets us train on synthetic, non-actionable examples (no real card/phone numbers),
  while still detecting real inputs (because real digits are normalized to 'D').
"""

import ast
import random
from typing import List, Tuple

import numpy as np
import tensorflow as tf
from sklearn.model_selection import train_test_split

# -----------------------------
# Config
# -----------------------------
SEED = 42
random.seed(SEED)
np.random.seed(SEED)
tf.random.set_seed(SEED)

MAX_LEN = 96  # bytes (UTF-8)
VOCAB_SIZE = 257  # 0=PAD, 1..256 = byte+1

# Labels
LBL_NORMAL = 0
LBL_PASSWORD = 1
LBL_EMAIL = 2
LBL_CREDIT_CARD = 3
LBL_PHONE_IL = 4

LABEL_NAMES = {
    0: "normal",
    1: "password",
    2: "email",
    3: "credit_card",
    4: "phone_il",
}


# -----------------------------
# Data loading
# -----------------------------
def load_file_tuples(path: str) -> List[Tuple[str, int]]:
    out: List[Tuple[str, int]] = []
    with open(path, "r", encoding="utf-8") as f:
        for line in f:
            line = line.rstrip(",\n")
            if not line:
                continue
            out.append(ast.literal_eval(line))
    return out


def normalize_text(s: str) -> str:
    # Replaces digits with 'D' to avoid training on real numbers and improve generalization.
    # Also collapses odd whitespace.
    s = "".join(("D" if ch.isdigit() else ch) for ch in s)
    s = " ".join(s.split())
    return s


def encode_text(text: str) -> np.ndarray:
    # UTF-8 bytes -> int ids in [1..256], 0 padding
    b = normalize_text(text).encode("utf-8")[:MAX_LEN]
    ids = np.zeros((MAX_LEN,), dtype=np.int32)
    for i, byte in enumerate(b):
        ids[i] = int(byte) + 1
    return ids


def build_dataset(data: List[Tuple[str, int]]):
    X = np.stack([encode_text(t) for t, _ in data], axis=0)
    y = np.array([lbl for _, lbl in data], dtype=np.int32)
    return X, y


# -----------------------------
# Model (byte-level CNN)
# -----------------------------
def build_model(num_classes: int) -> tf.keras.Model:
    inputs = tf.keras.layers.Input(shape=(MAX_LEN,), dtype=tf.int32)
    x = tf.keras.layers.Embedding(VOCAB_SIZE, 24, mask_zero=True)(inputs)
    x = tf.keras.layers.Conv1D(96, 5, activation="relu")(x)
    x = tf.keras.layers.GlobalMaxPooling1D()(x)
    x = tf.keras.layers.Dense(96, activation="relu")(x)
    x = tf.keras.layers.Dropout(0.35)(x)
    outputs = tf.keras.layers.Dense(num_classes, activation="softmax")(x)

    model = tf.keras.Model(inputs, outputs)
    model.compile(optimizer="adam", loss="sparse_categorical_crossentropy", metrics=["accuracy"])
    return model


def train_and_export():
    # Base training data txt files
    data = []
    data += load_file_tuples("normal_data.txt")  # label 0
    data += load_file_tuples("formatted_passwords.txt")  # label 1
    data += load_file_tuples("formatted_emails.txt")  # label 2
    data += load_file_tuples("formatted_credit_cards.txt")  # label 3
    data += load_file_tuples("formatted_phone_il.txt")  # label 4

    random.shuffle(data)

    X, y = build_dataset(data)
    num_classes = int(y.max()) + 1

    if num_classes != 5:
        raise ValueError(f"Expected 5 classes, got {num_classes}. Check your label values.")

    X_train, X_test, y_train, y_test = train_test_split(
        X, y, test_size=0.2, random_state=SEED, stratify=y
    )

    model = build_model(num_classes)

    callbacks = [
        tf.keras.callbacks.EarlyStopping(monitor="val_accuracy", patience=3, restore_best_weights=True),
    ]

    model.fit(
        X_train, y_train,
        validation_data=(X_test, y_test),
        epochs=20,
        batch_size=128,
        callbacks=callbacks,
        verbose=1,
    )

    loss, acc = model.evaluate(X_test, y_test, verbose=0)
    print(f"Test accuracy: {acc:.4f}")

    # Convert to TFLite (INT32 input stays INT32)
    converter = tf.lite.TFLiteConverter.from_keras_model(model)
    converter.optimizations = [tf.lite.Optimize.DEFAULT]
    tflite_model = converter.convert()

    out_path = "sensitive_data_detector.tflite"
    with open(out_path, "wb") as f:
        f.write(tflite_model)

    print(f"Saved: {out_path} ({len(tflite_model) / 1024:.2f} KB)")
    print(f"MAX_LEN={MAX_LEN}, labels={LABEL_NAMES}")


if __name__ == "__main__":
    train_and_export()
