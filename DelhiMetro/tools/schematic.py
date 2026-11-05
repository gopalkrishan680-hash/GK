"""Octilinear ("DMRC-style") schematic layout for the metro network.

Starts from the geographic positions and iteratively relaxes them so that:
  * every track segment points in one of 8 directions (0°, 45°, 90°, …),
  * consecutive stations are evenly spaced,
  * runs of a line are straight (smoothing),
  * unrelated stations don't overlap (repulsion),
  * the result stays close to geography (weak anchor), so it is still recognisable.

Pure numpy; deterministic.
"""
import math

import numpy as np


def layout(names, geo_xy, edges, line_runs, iterations=2500, max_len=2.5, min_gap=1.0):
    """
    names:     list of station names
    geo_xy:    {name: (x, y)} geographic positions (y grows downwards)
    edges:     set of (a, b) adjacent station pairs
    line_runs: list of station-name lists (each line), used for straightening
    returns    {name: (x, y)} schematic positions with unit station spacing
    """
    idx = {n: i for i, n in enumerate(names)}
    G = np.array([geo_xy[n] for n in names], dtype=float)
    E = np.array([(idx[a], idx[b]) for a, b in edges])
    # scale so the median edge is 1 unit
    lengths = np.linalg.norm(G[E[:, 0]] - G[E[:, 1]], axis=1)
    G = (G - G.mean(axis=0)) / np.median(lengths)
    P = G.copy()

    triples = []
    for run in line_runs:
        for a, b, c in zip(run, run[1:], run[2:]):
            triples.append((idx[a], idx[b], idx[c]))
    T = np.array(triples)

    adjacent = set()
    for a, b in E:
        adjacent.add((a, b))
        adjacent.add((b, a))

    n = len(names)
    step = math.pi / 4
    for it in range(iterations):
        t = it / iterations
        disp = np.zeros_like(P)
        cnt = np.zeros(n)

        # 1. octilinear + unit length edges
        v = P[E[:, 1]] - P[E[:, 0]]
        ang = np.arctan2(v[:, 1], v[:, 0])
        snapped = np.round(ang / step) * step
        # keep the current length (within limits) – only the direction is forced
        length = np.clip(np.linalg.norm(v, axis=1), 1.0, max_len)[:, None]
        target = np.stack([np.cos(snapped), np.sin(snapped)], axis=1) * length
        err = v - target
        w_edge = 0.3 + 0.5 * t
        np.add.at(disp, E[:, 0], w_edge * err / 2)
        np.add.at(disp, E[:, 1], -w_edge * err / 2)
        np.add.at(cnt, E[:, 0], 1)
        np.add.at(cnt, E[:, 1], 1)

        # 2. straighten runs (pull middle station towards neighbours' midpoint)
        w_smooth = 0.15 * (1 - t)
        mid = (P[T[:, 0]] + P[T[:, 2]]) / 2
        np.add.at(disp, T[:, 1], w_smooth * (mid - P[T[:, 1]]))

        # 3. weak pull to geography (fades out)
        disp += 0.02 * (1 - t) * (G - P)

        # 4. repulsion between non-adjacent stations closer than min_gap
        diff = P[:, None, :] - P[None, :, :]
        dist = np.linalg.norm(diff, axis=2) + np.eye(n)
        close = (dist < min_gap) & ~np.eye(n, dtype=bool)
        if close.any():
            ii, jj = np.nonzero(close)
            keep = np.array([(a, b) not in adjacent for a, b in zip(ii, jj)], dtype=bool)
            ii, jj = ii[keep], jj[keep]
            push = diff[ii, jj] / dist[ii, jj][:, None] * (min_gap - dist[ii, jj])[:, None] * 0.25
            np.add.at(disp, ii, push)

        P += disp

    # final cleanup: snap to a fine grid to make 0/45/90 degree segments crisp
    P = np.round(P * 4) / 4
    return {name: (float(P[i, 0]), float(P[i, 1])) for i, name in enumerate(names)}
