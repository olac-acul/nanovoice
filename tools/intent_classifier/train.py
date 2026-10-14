"""Trains the Italian local-intent classifier and exports it for the app.

Usage: python train.py <dir with data.py> <output dir>
Writes intent-it.bin (int32 buckets, int32 classes, float32 weights [buckets][classes], float32 bias [classes],
little endian) and parity.tsv (sentences with their probabilities, for the Kotlin parity test).
Features must match IntentClassifier.kt exactly: words, word bigrams, character 4-grams, FNV-1a hashed, L2-normalised.
"""
import sys, numpy as np, unicodedata, re
sys.path.insert(0, sys.argv[1])
import data
B = 1 << 14
LABELS = ["tell_time", "tell_date", "set_timer", "set_alarm", "other"]
def norm(t):
    t = unicodedata.normalize("NFD", t.lower()); t = "".join(c for c in t if unicodedata.category(c) != "Mn")
    return re.sub(r"\s+", " ", re.sub(r"[^0-9a-z]+", " ", t)).strip()
def fnv(s):
    h = 0x811C9DC5
    for b in s.encode("utf-8"):
        h ^= b; h = (h * 0x01000193) & 0xFFFFFFFF
    return h
def feats(t):
    w = norm(t).split(); f = ["w:" + x for x in w] + ["b:" + a + "_" + b for a, b in zip(w, w[1:])]
    s = " " + " ".join(w) + " "
    f += ["c:" + s[i:i + 4] for i in range(len(s) - 3)]
    v = {}
    for x in f:
        k = fnv(x) % B; v[k] = v.get(k, 0) + 1.0
    n = np.sqrt(sum(c * c for c in v.values())) or 1.0
    return {k: c / n for k, c in v.items()}
def mat(rows):
    X = np.zeros((len(rows), B), dtype=np.float32)
    for i, (t, _) in enumerate(rows):
        for k, c in feats(t).items(): X[i, k] = c
    y = np.array([LABELS.index(l) for _, l in rows]); return X, y
Xtr, ytr = mat(data.train); Xte, yte = mat(data.test)
W = np.zeros((B, len(LABELS)), dtype=np.float32); b = np.zeros(len(LABELS), dtype=np.float32)
rng = np.random.default_rng(0)
for epoch in range(80):
    idx = rng.permutation(len(ytr))
    for s in range(0, len(idx), 64):
        j = idx[s:s + 64]; z = Xtr[j] @ W + b; z -= z.max(1, keepdims=True); p = np.exp(z); p /= p.sum(1, keepdims=True)
        p[np.arange(len(j)), ytr[j]] -= 1
        W -= 2.0 * (Xtr[j].T @ p / len(j) + 1e-5 * W); b -= 2.0 * p.mean(0)
def predict(X):
    z = X @ W + b; z -= z.max(1, keepdims=True); p = np.exp(z); return p / p.sum(1, keepdims=True)
for name, X, y in [("addestramento", Xtr, ytr), ("frasi mai viste", Xte, yte)]:
    p = predict(X); print(f"{name}: accuratezza {np.mean(p.argmax(1) == y):.3f} su {len(y)}")
p = predict(Xte)
for th in (0.4, 0.5, 0.6, 0.7, 0.8):
    local_pred = (p.argmax(1) != LABELS.index("other")) & (p.max(1) >= th)
    wrong_local = np.sum(local_pred & (p.argmax(1) != yte)); missed = np.sum((yte != 4) & ~local_pred)
    print(f"soglia {th}: azioni locali sbagliate {wrong_local}, richieste locali mandate all'agente {missed} (su {np.sum(yte != 4)})")
errs = [(data.test[i][0], data.test[i][1], LABELS[p[i].argmax()], round(float(p[i].max()), 2)) for i in range(len(yte)) if p[i].argmax() != yte[i]]
print("errori:", sorted(set(errs))[:20])


import struct, os
out = sys.argv[2]
with open(os.path.join(out, "intent-it.bin"), "wb") as f:
    f.write(struct.pack("<ii", B, len(LABELS)))
    f.write(W.astype("<f4").tobytes(order="C"))
    f.write(b.astype("<f4").tobytes())
samples = ["che ore sono", "fai partire sul telefono un conto alla rovescia di tre minuti", "domattina svegliami alle sei e mezza",
           "che giorno è oggi", "che ore sono a tokyo", "ricordami di comprare il latte alle cinque", "leggimi le email",
           "ehi mi metti un countdown di cinque minuti per favore", "qual è la capitale del burundi", "Un'ora e un quarto? È già tardi!"]
Xs = np.zeros((len(samples), B), dtype=np.float32)
for i, t in enumerate(samples):
    for k, c in feats(t).items(): Xs[i, k] = c
ps = predict(Xs)
with open(os.path.join(out, "parity.tsv"), "w") as f:
    for t, pr in zip(samples, ps): f.write(t + "\t" + "\t".join(f"{x:.6f}" for x in pr) + "\n")
print("esportato in", out)
