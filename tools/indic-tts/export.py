"""Turn AI4Bharat Indic-TTS Hindi (FastPitch + HiFiGAN, CC-BY-4.0) into one ONNX file for the Stickman Studio app.

    python export.py <unzipped hi folder> <out dir>

Writes indic-hi.onnx (inputs: tokens int64 [1, n], speaker int64 [1], speed float [1]; output: 22.05 kHz audio)
and indic-hi.json (the character vocabulary and the speaker ids). Before anything is loaded, the checkpoints are
checked to hold only weights (a .pth file can carry code).
"""
import json
import math
import sys

import numpy as np
import torch

m, out = sys.argv[1], sys.argv[2]

# a .pth is a pickle inside a zip: allow only the globals that plain weights need
import io, pickletools, zipfile  # noqa: E402
SAFE = {'collections OrderedDict', 'torch FloatStorage', 'torch LongStorage', 'torch._utils _rebuild_tensor_v2', 'builtins set'}
for f in ['fastpitch/best_model.pth', 'hifigan/best_model.pth', 'fastpitch/speakers.pth']:
    z = zipfile.ZipFile(f'{m}/{f}')
    for n in z.namelist():
        if not n.endswith('.pkl'): continue
        ops = list(pickletools.genops(io.BytesIO(z.read(n))))
        for i, (op, arg, _) in enumerate(ops):
            g = str(arg) if op.name in ('GLOBAL', 'INST') else f'{ops[i-2][1]} {ops[i-1][1]}' if op.name == 'STACK_GLOBAL' else None
            if g and g not in SAFE: sys.exit(f'{f}: unexpected {g} in the checkpoint, not loading it')
print('checkpoints hold only weights')

# the config points at the training machine's speakers file: use the one in the download
cfg = json.load(open(f'{m}/fastpitch/config.json'))
def fix(o):
    if isinstance(o, dict): return {k: fix(v) for k, v in o.items()}
    if isinstance(o, list): return [fix(v) for v in o]
    if isinstance(o, str) and o.endswith('speakers.pth'): return f'{m}/fastpitch/speakers.pth'
    return o
json.dump(fix(cfg), open(f'{out}/fp-config.json', 'w'))

from TTS.utils.synthesizer import Synthesizer  # noqa: E402
from TTS.tts.layers.generic.transformer import FFTransformer  # noqa: E402

s = Synthesizer(tts_checkpoint=f'{m}/fastpitch/best_model.pth', tts_config_path=f'{out}/fp-config.json',
                tts_speakers_file=f'{m}/fastpitch/speakers.pth', vocoder_checkpoint=f'{m}/hifigan/best_model.pth',
                vocoder_config=f'{m}/hifigan/config.json', use_cuda=False)
fp, voc = s.tts_model.eval(), s.vocoder_model.eval()
spk = s.tts_model.speaker_manager.name_to_id


class Attn(torch.nn.Module):
    """nn.MultiheadAttention (1 head) without fixed lengths: the ONNX graph then takes any text length.
    Same maths; at inference every position is real text, so the masks are all open."""
    def __init__(self, mha):
        super().__init__()
        assert mha.num_heads == 1 and mha._qkv_same_embed_dim
        self.w, self.b, self.o = mha.in_proj_weight, mha.in_proj_bias, mha.out_proj
        self.scale = 1.0 / math.sqrt(mha.embed_dim)

    def forward(self, q, k, v, attn_mask=None, key_padding_mask=None):
        x = torch.nn.functional.linear(q.transpose(0, 1), self.w, self.b)
        qq, kk, vv = x.chunk(3, dim=-1)
        a = torch.softmax(torch.matmul(qq * self.scale, kk.transpose(1, 2)), dim=-1)
        return self.o(torch.matmul(a, vv)).transpose(0, 1), a


text = 'लोग हँसते थे, कहते थे, चाय वाले का बेटा चाय ही बेचेगा.'
ids = torch.tensor([s.tts_model.tokenizer.text_to_ids(text)])
with torch.no_grad():
    before = fp.inference(ids, aux_input={'speaker_ids': torch.tensor([spk['male']]), 'd_vectors': None})['model_outputs']
for mod in list(fp.modules()):
    if isinstance(mod, FFTransformer):
        mod.self_attn = Attn(mod.self_attn)
with torch.no_grad():
    after = fp.inference(ids, aux_input={'speaker_ids': torch.tensor([spk['male']]), 'd_vectors': None})['model_outputs']
assert before.shape == after.shape and float((before - after).abs().max()) < 1e-4, 'attention swap changed the output'


class W(torch.nn.Module):
    def __init__(self):
        super().__init__()
        self.fp, self.voc = fp, voc

    def forward(self, tokens, speaker, speed):
        self.fp.length_scale = 1.0 / speed
        mel = self.fp.inference(tokens, aux_input={'speaker_ids': speaker, 'd_vectors': None})['model_outputs']
        return self.voc.inference(mel.transpose(1, 2)).reshape(-1)


w = W().eval()
path = f'{out}/indic-hi.onnx'
torch.onnx.export(w, (ids, torch.tensor([spk['male']]), torch.tensor([1.0])), path,
                  input_names=['tokens', 'speaker', 'speed'], output_names=['audio'],
                  dynamic_axes={'tokens': {1: 'n'}, 'audio': {0: 'samples'}}, opset_version=17, dynamo=False)

# check: other lengths, speakers and speeds give the same audio as PyTorch
import onnxruntime as ort  # noqa: E402
sess = ort.InferenceSession(path)
for t, sp, speed in [(text, 'male', 1.0), ('पर क्या सच में ऐसा हुआ?', 'female', 1.2), ('रोहन के पापा स्टेशन पर चाय बेचते थे.', 'male', 0.9)]:
    tid = s.tts_model.tokenizer.text_to_ids(t)
    o = sess.run(None, {'tokens': np.array([tid], np.int64), 'speaker': np.array([spk[sp]], np.int64), 'speed': np.array([speed], np.float32)})[0]
    with torch.no_grad():
        r = w(torch.tensor([tid]), torch.tensor([spk[sp]]), torch.tensor([speed])).numpy()
    assert len(o) == len(r) and float(np.abs(o - r).max()) < 1e-2, f'ONNX differs for {t!r}'
    print('ok', sp, speed, len(o), 'samples')

json.dump({'vocab': list(s.tts_model.tokenizer.characters.vocab), 'speakers': spk, 'sample_rate': 22050,
           'source': 'AI4Bharat Indic-TTS v1 Hindi (FastPitch + HiFiGAN), CC-BY-4.0'},
          open(f'{out}/indic-hi.json', 'w'), ensure_ascii=False)
print('wrote', path)
