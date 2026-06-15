# -*- coding: utf-8 -*-
"""
离线复现 StandardTestActivity 的检测+稳定器管线, 用 test.mp4 对比
旧稳定器 vs 新稳定器 的行为(尤其瞬态缺陷是否被抹掉 / 报表计数差异)。

忠实移植自:
  Detector.kt        -> letterbox + parseRawWithNms (conf0.25, nms iou0.45)
  DetectionStabilizer.kt (旧/新两版)
  StandardTestActivity.kt -> stepMs=500, scaleForDetection(maxSide<=1600), newlyConfirmed 计数
"""
import os, math, sys
os.environ["TF_CPP_MIN_LOG_LEVEL"] = "3"
import numpy as np
import cv2
from ai_edge_litert.interpreter import Interpreter

MODEL = "android-app/app/src/main/assets/models/WeldSpatter_Default.tflite"
LABELS = ["weld", "spatter"]
VIDEO = "test.mp4"
CONF = 0.25
NMS_IOU = 0.45
INPUT = 640


# ----------------------------- RectF -----------------------------
class RectF:
    __slots__ = ("left", "top", "right", "bottom")
    def __init__(self, l, t, r, b):
        self.left, self.top, self.right, self.bottom = l, t, r, b
    def width(self):  return self.right - self.left
    def height(self): return self.bottom - self.top
    def cx(self): return (self.left + self.right) / 2
    def cy(self): return (self.top + self.bottom) / 2
    def copy(self): return RectF(self.left, self.top, self.right, self.bottom)


class Detection:
    __slots__ = ("box", "label", "score")
    def __init__(self, box, label, score):
        self.box, self.label, self.score = box, label, score


def iou(a, b):
    ix = max(0.0, min(a.right, b.right) - max(a.left, b.left))
    iy = max(0.0, min(a.bottom, b.bottom) - max(a.top, b.top))
    inter = ix * iy
    union = a.width() * a.height() + b.width() * b.height() - inter
    return 0.0 if union <= 0 else inter / union


# ----------------------------- Detector -----------------------------
class Detector:
    def __init__(self, path):
        self.it = Interpreter(model_path=path)
        self.it.allocate_tensors()
        self.inp = self.it.get_input_details()[0]
        self.out = self.it.get_output_details()[0]
        self.input_size = int(self.inp["shape"][1])

    def detect(self, img):  # img: HxWx3 BGR uint8 (original coords)
        h, w = img.shape[:2]
        scale = min(self.input_size / w, self.input_size / h)
        nw, nh = int(w * scale), int(h * scale)
        padx = (self.input_size - nw) / 2
        pady = (self.input_size - nh) / 2
        resized = cv2.resize(img, (nw, nh), interpolation=cv2.INTER_LINEAR)
        canvas = np.full((self.input_size, self.input_size, 3), 114, np.uint8)
        canvas[int(pady):int(pady) + nh, int(padx):int(padx) + nw] = resized
        rgb = cv2.cvtColor(canvas, cv2.COLOR_BGR2RGB).astype(np.float32) / 255.0
        self.it.set_tensor(self.inp["index"], rgb[None])
        self.it.invoke()
        o = self.it.get_tensor(self.out["index"])[0]  # [6, 8400]
        cand = self._parse_raw(o)
        cand = self._nms(cand)
        # map letterbox -> original
        res = []
        for box, sc, cls in cand:
            r = RectF(
                min(max((box.left - padx) / scale, 0), w),
                min(max((box.top - pady) / scale, 0), h),
                min(max((box.right - padx) / scale, 0), w),
                min(max((box.bottom - pady) / scale, 0), h),
            )
            if r.width() < 1 or r.height() < 1:
                continue
            res.append(Detection(r, LABELS[cls] if cls < len(LABELS) else f"cls{cls}", sc))
        return res

    def _parse_raw(self, o):  # o: [channels=6, anchors]
        channels, anchors = o.shape
        cand = []
        for i in range(anchors):
            best, bestcls = 0.0, 0
            for c in range(4, channels):
                v = o[c, i]
                if v > best:
                    best, bestcls = v, c - 4
            if best < CONF:
                continue
            s = INPUT if o[2, i] <= 1.5 else 1.0
            cx, cy, ww, hh = o[0, i] * s, o[1, i] * s, o[2, i] * s, o[3, i] * s
            cand.append((RectF(cx - ww/2, cy - hh/2, cx + ww/2, cy + hh/2), float(best), bestcls))
        return cand

    def _nms(self, boxes):
        boxes = sorted(boxes, key=lambda x: x[1], reverse=True)
        keep = []
        while boxes:
            top = boxes.pop(0)
            keep.append(top)
            boxes = [b for b in boxes if not (iou(top[0], b[0]) > NMS_IOU and b[2] == top[2])]
        return keep


# ----------------------------- OLD stabilizer -----------------------------
class OldStab:
    def __init__(self, minHits=2, maxMisses=2, iouTh=0.35, smoothing=0.65):
        self.minHits, self.maxMisses, self.iouTh, self.sm = minHits, maxMisses, iouTh, smoothing
        self.tracks = []; self.nid = 1
    def _smooth(self, p, c):
        k = self.sm; a = 1 - k
        return RectF(p.left*k+c.left*a, p.top*k+c.top*a, p.right*k+c.right*a, p.bottom*k+c.bottom*a)
    def update(self, raw):
        for t in self.tracks: t["misses"] += 1
        matched = set()
        for d in sorted(raw, key=lambda x: x.score, reverse=True):
            cands = [t for t in self.tracks if t["label"] == d.label and t["id"] not in matched]
            best = max(cands, key=lambda t: iou(t["box"], d.box), default=None)
            if best is not None and iou(best["box"], d.box) >= self.iouTh:
                best["box"] = self._smooth(best["box"], d.box)
                best["score"] = max(best["score"]*0.7 + d.score*0.3, d.score)
                best["hits"] += 1; best["misses"] = 0; matched.add(best["id"])
            else:
                self.tracks.append(dict(id=self.nid, label=d.label, box=d.box.copy(),
                                        score=d.score, hits=1, misses=0, confirmed=False)); self.nid += 1
        newly = []
        for t in self.tracks:
            if not t["confirmed"] and t["hits"] >= self.minHits and t["misses"] == 0:
                t["confirmed"] = True; newly.append(t)
        self.tracks = [t for t in self.tracks if t["misses"] <= self.maxMisses]
        disp = [t for t in self.tracks if t["confirmed"]]
        return disp, newly


# ----------------------------- NEW stabilizer -----------------------------
class NewStab:
    def __init__(self, minHits=1, instantThreshold=0.45, maxMisses=2, displayMisses=1,
                 matchIou=0.2, matchCenterRatio=0.8, minSmoothing=0.2, maxSmoothing=0.6):
        self.minHits, self.inst, self.maxMisses, self.dispMiss = minHits, instantThreshold, maxMisses, displayMisses
        self.mIou, self.mCenter, self.minSm, self.maxSm = matchIou, matchCenterRatio, minSmoothing, maxSmoothing
        self.tracks = []; self.nid = 1
    def _center_close(self, a, b):
        dx, dy = a.cx()-b.cx(), a.cy()-b.cy()
        dist = math.hypot(dx, dy)
        scale = (a.width()+a.height()+b.width()+b.height())/4
        if scale <= 0: return 0.0
        limit = scale*self.mCenter
        return 0.0 if dist >= limit else 1 - dist/limit
    def _best(self, d, matched):
        best, bs = None, 0.0
        for t in self.tracks:
            if t["label"] != d.label or t["id"] in matched: continue
            ov = iou(t["box"], d.box); cl = self._center_close(t["box"], d.box)
            if ov < self.mIou and cl <= 0: continue
            s = max(ov, cl)
            if s > bs: bs, best = s, t
        return best
    def _smooth(self, p, c):
        dist = math.hypot(p.cx()-c.cx(), p.cy()-c.cy())
        scale = (c.width()+c.height())/2
        motion = 0.0 if scale <= 0 else min(max(dist/scale, 0), 1)
        k = self.maxSm - (self.maxSm-self.minSm)*motion; a = 1-k
        return RectF(p.left*k+c.left*a, p.top*k+c.top*a, p.right*k+c.right*a, p.bottom*k+c.bottom*a)
    def update(self, raw):
        for t in self.tracks: t["misses"] += 1
        matched = set(); newly = []
        for d in sorted(raw, key=lambda x: x.score, reverse=True):
            ex = self._best(d, matched)
            if ex is None:
                t = dict(id=self.nid, label=d.label, box=d.box.copy(), score=d.score,
                         hits=1, misses=0, confirmed=False); self.nid += 1; self.tracks.append(t)
            else:
                ex["box"] = self._smooth(ex["box"], d.box)
                ex["score"] = max(ex["score"]*0.6 + d.score*0.4, d.score)
                ex["hits"] += 1; ex["misses"] = 0; t = ex
            matched.add(t["id"])
            if not t["confirmed"] and (t["hits"] >= self.minHits or t["score"] >= self.inst):
                t["confirmed"] = True; newly.append(t)
        self.tracks = [t for t in self.tracks if t["misses"] <= self.maxMisses]
        disp = [t for t in self.tracks if t["confirmed"] and t["misses"] <= self.dispMiss]
        return disp, newly


def scale_for_detection(img):
    h, w = img.shape[:2]
    m = max(h, w)
    if m <= 1600: return img
    s = 1600/m
    return cv2.resize(img, (int(w*s), int(h*s)))


def is_defect(label):  # StandardTestActivity.isDefect: 非 Normal Weld 即缺陷; 这里 weld/spatter 都算
    return "normal weld" not in label.lower()


def main():
    det = Detector(MODEL)
    cap = cv2.VideoCapture(VIDEO)
    fps = cap.get(cv2.CAP_PROP_FPS)
    nframes = cap.get(cv2.CAP_PROP_FRAME_COUNT)
    dur_ms = (nframes / fps * 1000) if fps else 0
    print(f"video: {nframes:.0f} frames @ {fps:.2f}fps  dur={dur_ms/1000:.2f}s")

    old = OldStab(minHits=2, maxMisses=2)
    new = NewStab(minHits=1, instantThreshold=0.45, maxMisses=2, displayMisses=0, matchCenterRatio=0.8)
    step = 500; t = 0
    rows = []
    while t <= dur_ms:
        cap.set(cv2.CAP_PROP_POS_MSEC, t)
        ok, frame = cap.read()
        if not ok: break
        img = scale_for_detection(frame)
        raw = det.detect(img)
        od, on = old.update([Detection(d.box.copy(), d.label, d.score) for d in raw])
        nd, nn = new.update([Detection(d.box.copy(), d.label, d.score) for d in raw])
        rows.append((t/1000, len(raw),
                     sum(is_defect(d.label) for d in raw),
                     len(od), len([x for x in on if is_defect(x["label"])]),
                     len(nd), len([x for x in nn if is_defect(x["label"])])))
        t += step
    cap.release()

    print("\n  t(s) | raw det | raw defect | OLD disp | OLD new+ | NEW disp | NEW new+")
    print("  " + "-"*68)
    old_total = new_total = 0
    for (ts, nr, nrd, odl, onl, ndl, nnl) in rows:
        old_total += onl; new_total += nnl
        flag = "  <-- raw has defect, OLD dropped it" if (nrd > 0 and onl == 0 and ndl > 0) else ""
        print(f"  {ts:5.1f} |   {nr:3d}   |    {nrd:3d}    |   {odl:3d}    |   {onl:3d}    |   {ndl:3d}    |   {nnl:3d}{flag}")
    print("  " + "-"*68)
    print(f"  report defect count (sum newlyConfirmed):  OLD={old_total}   NEW={new_total}")
    raw_defect_frames = sum(1 for r in rows if r[2] > 0)
    print(f"  frames with raw defects: {raw_defect_frames} / {len(rows)}")
    # disp-vs-raw fidelity: how close drawn boxes track the actual detections
    old_gap = sum(abs(r[3]-r[1]) for r in rows); new_gap = sum(abs(r[5]-r[1]) for r in rows)
    print(f"  sum|disp-raw| (lower=tracks detections better):  OLD={old_gap}   NEW={new_gap}")


if __name__ == "__main__":
    main()
