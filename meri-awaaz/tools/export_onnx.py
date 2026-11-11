#!/usr/bin/env python3
"""Export the OpenVoice V2 tone-colour converter to two ONNX files for the app.

  tone_encoder.onnx : audio[1,N] (22.05 kHz mono, -1..1)          -> g[1,256]
  converter.onnx    : audio[1,N], g_src[1,256], g_tgt[1,256]      -> audio[1,1,N]

The STFT is baked into the graphs, so the app only has to feed raw samples.

Usage:
  export_onnx.py --out DIR [--checkpoint checkpoint.pth --config config.json]

The checkpoint is OpenVoice V2's `converter/checkpoint.pth` (MIT licence, MyShell.ai).
The network below is a minimal re-statement of the parts of that model which voice
conversion uses (the standard VITS posterior encoder, flow and HiFi-GAN decoder, plus
OpenVoice's reference encoder); parameter names match the checkpoint. Without
--checkpoint the weights are random, which is only useful for testing the export.
"""
import argparse
import json
import os

import numpy as np
import torch
from torch import nn
from torch.nn import functional as F
from torch.nn.utils import remove_weight_norm, weight_norm

DEFAULT_CONFIG = {
    "data": {"sampling_rate": 22050, "filter_length": 1024, "hop_length": 256,
             "win_length": 1024, "n_speakers": 0},
    "model": {"zero_g": True, "inter_channels": 192, "hidden_channels": 192,
              "resblock": "1", "resblock_kernel_sizes": [3, 7, 11],
              "resblock_dilation_sizes": [[1, 3, 5], [1, 3, 5], [1, 3, 5]],
              "upsample_rates": [8, 8, 2, 2], "upsample_initial_channel": 512,
              "upsample_kernel_sizes": [16, 16, 4, 4], "gin_channels": 256},
}
TAU = 0.3  # same default as OpenVoice's ToneColorConverter.convert
LRELU_SLOPE = 0.1


class WN(nn.Module):
    def __init__(self, hidden, kernel, n_layers, gin):
        super().__init__()
        self.hidden, self.n_layers = hidden, n_layers
        self.cond_layer = weight_norm(nn.Conv1d(gin, 2 * hidden * n_layers, 1))
        self.in_layers = nn.ModuleList(
            weight_norm(nn.Conv1d(hidden, 2 * hidden, kernel, padding=kernel // 2))
            for _ in range(n_layers))
        self.res_skip_layers = nn.ModuleList(
            weight_norm(nn.Conv1d(hidden, 2 * hidden if i < n_layers - 1 else hidden, 1))
            for i in range(n_layers))

    def forward(self, x, g):
        h = self.hidden
        g = self.cond_layer(g)
        output = torch.zeros_like(x)
        for i in range(self.n_layers):
            a = self.in_layers[i](x) + g[:, i * 2 * h:(i + 1) * 2 * h, :]
            acts = torch.tanh(a[:, :h]) * torch.sigmoid(a[:, h:])
            rs = self.res_skip_layers[i](acts)
            if i < self.n_layers - 1:
                x = x + rs[:, :h]
                output = output + rs[:, h:]
            else:
                output = output + rs
        return output


class PosteriorEncoder(nn.Module):
    def __init__(self, in_channels, out_channels, hidden, gin):
        super().__init__()
        self.out_channels = out_channels
        self.pre = nn.Conv1d(in_channels, hidden, 1)
        self.enc = WN(hidden, 5, 16, gin)
        self.proj = nn.Conv1d(hidden, out_channels * 2, 1)

    def forward(self, spec, g, tau):
        stats = self.proj(self.enc(self.pre(spec), g))
        m, logs = torch.split(stats, self.out_channels, dim=1)
        if tau > 0:
            return m + torch.randn_like(m) * tau * torch.exp(logs)
        return m


class ResidualCouplingLayer(nn.Module):  # mean-only variant
    def __init__(self, channels, hidden, gin):
        super().__init__()
        self.half = channels // 2
        self.pre = nn.Conv1d(self.half, hidden, 1)
        self.enc = WN(hidden, 5, 4, gin)
        self.post = nn.Conv1d(hidden, self.half, 1)

    def forward(self, x, g, reverse):
        x0, x1 = x[:, :self.half], x[:, self.half:]
        m = self.post(self.enc(self.pre(x0), g))
        return torch.cat([x0, x1 - m if reverse else x1 + m], 1)


class Flip(nn.Module):
    def forward(self, x, g, reverse):
        return torch.flip(x, [1])


class ResidualCouplingBlock(nn.Module):
    def __init__(self, channels, hidden, gin, n_flows=4):
        super().__init__()
        self.flows = nn.ModuleList()
        for _ in range(n_flows):
            self.flows.append(ResidualCouplingLayer(channels, hidden, gin))
            self.flows.append(Flip())

    def forward(self, x, g, reverse=False):
        for flow in (reversed(self.flows) if reverse else self.flows):
            x = flow(x, g, reverse)
        return x


class ResBlock1(nn.Module):
    def __init__(self, ch, kernel, dilations):
        super().__init__()
        self.convs1 = nn.ModuleList(
            weight_norm(nn.Conv1d(ch, ch, kernel, dilation=d, padding=(kernel * d - d) // 2))
            for d in dilations)
        self.convs2 = nn.ModuleList(
            weight_norm(nn.Conv1d(ch, ch, kernel, padding=kernel // 2)) for _ in dilations)

    def forward(self, x):
        for c1, c2 in zip(self.convs1, self.convs2):
            xt = c1(F.leaky_relu(x, LRELU_SLOPE))
            x = c2(F.leaky_relu(xt, LRELU_SLOPE)) + x
        return x


class Generator(nn.Module):  # HiFi-GAN
    def __init__(self, in_ch, kernels, dilations, rates, up_ch, up_kernels, gin):
        super().__init__()
        self.num_kernels = len(kernels)
        self.conv_pre = nn.Conv1d(in_ch, up_ch, 7, padding=3)
        self.ups = nn.ModuleList(
            weight_norm(nn.ConvTranspose1d(up_ch // 2 ** i, up_ch // 2 ** (i + 1), k, u,
                                           padding=(k - u) // 2))
            for i, (u, k) in enumerate(zip(rates, up_kernels)))
        self.resblocks = nn.ModuleList()
        for i in range(len(rates)):
            ch = up_ch // 2 ** (i + 1)
            for k, d in zip(kernels, dilations):
                self.resblocks.append(ResBlock1(ch, k, d))
        self.conv_post = nn.Conv1d(ch, 1, 7, padding=3, bias=False)
        self.cond = nn.Conv1d(gin, up_ch, 1)

    def forward(self, x, g):
        x = self.conv_pre(x) + self.cond(g)
        for i, up in enumerate(self.ups):
            x = up(F.leaky_relu(x, LRELU_SLOPE))
            blocks = self.resblocks[i * self.num_kernels:(i + 1) * self.num_kernels]
            x = sum(b(x) for b in blocks) / self.num_kernels
        return torch.tanh(self.conv_post(F.leaky_relu(x)))


class ReferenceEncoder(nn.Module):
    def __init__(self, spec_channels, gin):
        super().__init__()
        filters = [1, 32, 32, 64, 64, 128, 128]
        self.convs = nn.ModuleList(
            weight_norm(nn.Conv2d(filters[i], filters[i + 1], 3, stride=2, padding=1))
            for i in range(6))
        width = spec_channels
        for _ in range(6):
            width = (width - 1) // 2 + 1
        self.gru = nn.GRU(128 * width, 128, batch_first=True)
        self.proj = nn.Linear(128, gin)
        self.layernorm = nn.LayerNorm(spec_channels)

    def forward(self, spec):  # [1, T, bins]
        out = self.layernorm(spec).unsqueeze(1)
        for conv in self.convs:
            out = F.relu(conv(out))
        out = out.transpose(1, 2).flatten(2)  # [1, T', 128 * width]
        _, h = self.gru(out)
        return self.proj(h.squeeze(0))


class VoiceModel(nn.Module):
    def __init__(self, spec_channels, m):
        super().__init__()
        assert m["resblock"] == "1"
        gin = m["gin_channels"]
        self.zero_g = m.get("zero_g", False)
        self.dec = Generator(m["inter_channels"], m["resblock_kernel_sizes"],
                             m["resblock_dilation_sizes"], m["upsample_rates"],
                             m["upsample_initial_channel"], m["upsample_kernel_sizes"], gin)
        self.enc_q = PosteriorEncoder(spec_channels, m["inter_channels"],
                                      m["hidden_channels"], gin)
        self.flow = ResidualCouplingBlock(m["inter_channels"], m["hidden_channels"], gin)
        self.ref_enc = ReferenceEncoder(spec_channels, gin)


class ConvSTFT(nn.Module):
    """Magnitude spectrogram identical to the torch.stft based one OpenVoice uses."""

    def __init__(self, n_fft, hop, win):
        super().__init__()
        assert win == n_fft
        self.hop = hop
        self.pad = (n_fft - hop) // 2
        self.bins = n_fft // 2 + 1
        basis = torch.fft.fft(torch.eye(n_fft, dtype=torch.float64))[: self.bins]
        window = torch.hann_window(win, dtype=torch.float64)
        kernel = torch.cat([basis.real, basis.imag], 0) * window  # [2*bins, n_fft]
        self.register_buffer("kernel", kernel.float().unsqueeze(1))

    def forward(self, audio):  # [1, N]
        y = F.pad(audio.unsqueeze(1), (self.pad, self.pad), mode="reflect")
        c = F.conv1d(y, self.kernel, stride=self.hop)  # [1, 2*bins, T]
        re, im = c[:, : self.bins], c[:, self.bins:]
        return torch.sqrt(re * re + im * im + 1e-6)


class ToneEncoder(nn.Module):
    def __init__(self, model, stft):
        super().__init__()
        self.stft, self.ref_enc = stft, model.ref_enc

    def forward(self, audio):
        return self.ref_enc(self.stft(audio).transpose(1, 2))


class Converter(nn.Module):
    def __init__(self, model, stft, tau):
        super().__init__()
        self.stft, self.tau, self.model = stft, tau, model

    def forward(self, audio, g_src, g_tgt):
        m = self.model
        g_src, g_tgt = g_src.unsqueeze(-1), g_tgt.unsqueeze(-1)
        z = m.enc_q(self.stft(audio), torch.zeros_like(g_src) if m.zero_g else g_src, self.tau)
        z_hat = m.flow(m.flow(z, g_src), g_tgt, reverse=True)
        return m.dec(z_hat, torch.zeros_like(g_tgt) if m.zero_g else g_tgt)


def strip_weight_norm(model):
    n = 0
    for mod in model.modules():
        if hasattr(mod, "weight_g"):
            remove_weight_norm(mod)
            n += 1
    return n


def reference_spectrogram(y, n_fft, hop):
    """The spectrogram exactly as OpenVoice computes it (torch.stft based)."""
    pad = (n_fft - hop) // 2
    y = F.pad(y.unsqueeze(1), (pad, pad), mode="reflect").squeeze(1)
    c = torch.stft(y, n_fft, hop_length=hop, win_length=n_fft, window=torch.hann_window(n_fft),
                   center=False, normalized=False, onesided=True, return_complex=True)
    return torch.sqrt(c.real ** 2 + c.imag ** 2 + 1e-6)


def export(module, args, path, inputs, output):
    torch.onnx.export(
        module, args, path, input_names=inputs, output_names=[output],
        dynamic_axes={"audio": {1: "samples"}}, opset_version=17,
        do_constant_folding=True, dynamo=False,
    )


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--out", required=True)
    ap.add_argument("--checkpoint")
    ap.add_argument("--config")
    a = ap.parse_args()

    cfg = json.load(open(a.config)) if a.config else DEFAULT_CONFIG
    d = cfg["data"]
    assert d["sampling_rate"] == 22050 and d["hop_length"] == 256, d
    assert d["win_length"] == d["filter_length"] and d["n_speakers"] == 0, d
    n_fft, hop = d["filter_length"], d["hop_length"]
    torch.manual_seed(0)
    model = VoiceModel(n_fft // 2 + 1, cfg["model"]).eval()
    if a.checkpoint:
        sd = torch.load(a.checkpoint, map_location="cpu", weights_only=True)["model"]
        missing, unexpected = model.load_state_dict(sd, strict=False)
        print("missing keys:", missing, "\nunexpected keys:", unexpected)
        assert not missing, "checkpoint does not match the model definition"
        assert not [k for k in unexpected if k.split(".")[0] in ("dec", "enc_q", "flow", "ref_enc")]
    else:
        print("WARNING: no checkpoint given, exporting RANDOM weights")

    stft = ConvSTFT(n_fft, hop, d["win_length"]).eval()
    rng = np.random.default_rng(0)
    lengths = [22050 * 3 + 100, 256 * 150, 22050 * 5]
    audios = [torch.from_numpy((rng.standard_normal(n) * 0.1).astype(np.float32))[None]
              for n in lengths]

    # PyTorch reference results, taken before anything is folded or exported.
    with torch.no_grad():
        for y in audios:
            err = float((stft(y) - reference_spectrogram(y, n_fft, hop)).abs().max())
            assert err < 1e-3, f"conv STFT differs from torch.stft by {err}"
        ref_g = [model.ref_enc(reference_spectrogram(y, n_fft, hop).transpose(1, 2)) for y in audios]
        ref_o = [Converter(model, stft, 0.0)(y, ref_g[i], ref_g[(i + 1) % 3]).numpy()
                 for i, y in enumerate(audios)]

    print("weight-norm layers folded:", strip_weight_norm(model))
    os.makedirs(a.out, exist_ok=True)
    enc_path = os.path.join(a.out, "tone_encoder.onnx")
    conv_path = os.path.join(a.out, "converter.onnx")
    check_path = os.path.join(a.out, "_converter_tau0.onnx")
    g = torch.zeros(1, cfg["model"]["gin_channels"])
    with torch.no_grad():
        export(ToneEncoder(model, stft), (audios[0],), enc_path, ["audio"], "g")
        export(Converter(model, stft, 0.0), (audios[0], g, g), check_path,
               ["audio", "g_src", "g_tgt"], "converted")
        export(Converter(model, stft, TAU), (audios[0], g, g), conv_path,
               ["audio", "g_src", "g_tgt"], "converted")

    # Verify the ONNX graphs against PyTorch, at lengths other than the traced one.
    import onnxruntime as ort
    enc = ort.InferenceSession(enc_path, providers=["CPUExecutionProvider"])
    chk = ort.InferenceSession(check_path, providers=["CPUExecutionProvider"])
    fin = ort.InferenceSession(conv_path, providers=["CPUExecutionProvider"])
    for i, y in enumerate(audios):
        g_i = enc.run(None, {"audio": y.numpy()})[0]
        err_g = float(np.abs(g_i - ref_g[i].numpy()).max())
        feed = {"audio": y.numpy(), "g_src": ref_g[i].numpy(),
                "g_tgt": ref_g[(i + 1) % 3].numpy()}
        o = chk.run(None, feed)[0]
        err_o = float(np.abs(o - ref_o[i]).max())
        o2 = fin.run(None, feed)[0]
        frames = lengths[i] // 256
        print(f"len={lengths[i]} encoder_err={err_g:.2e} converter_err={err_o:.2e} "
              f"out_shape={o2.shape}")
        assert err_g < 1e-3, "tone encoder ONNX differs from PyTorch"
        assert err_o < 1e-2, "converter ONNX differs from PyTorch"
        assert o.shape == o2.shape == (1, 1, frames * 256), (o.shape, o2.shape)
        assert np.isfinite(o2).all()
    os.remove(check_path)
    for p in (enc_path, conv_path):
        print(f"{p}: {os.path.getsize(p) / 1e6:.1f} MB")
    print("EXPORT OK")


if __name__ == "__main__":
    main()
