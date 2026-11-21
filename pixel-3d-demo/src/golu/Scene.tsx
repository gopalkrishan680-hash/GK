import { useThree } from "@react-three/fiber";
import React, { useMemo } from "react";
import { Easing, interpolate, interpolateColors, useCurrentFrame } from "remotion";
import * as THREE from "three";

// ---------- timeline (30fps) ----------
export const T = {
  wake: 150,
  wave: 205,
  starIn: 270,
  hopStart: 330,
  hopEnd: 540,
  catchPeak: 572,
  land: 600,
  hug: 640,
  sunsetStart: 520,
  sunsetEnd: 760,
  outro: 790,
};

const clamp = { extrapolateLeft: "clamp", extrapolateRight: "clamp" } as const;
const ease = Easing.inOut(Easing.cubic);

const HOP_X_END = 3.2;
const HOPS = 4;
export const STAR_FLOAT: [number, number, number] = [3.6, 2.4, 0.2];

// ---------- Golu's motion ----------
const goluState = (f: number) => {
  let x = 0;
  let y = 0;
  let sx = 1;
  let sy = 1;
  if (f >= T.hopStart && f < T.hopEnd) {
    const len = (T.hopEnd - T.hopStart) / HOPS;
    const i = Math.floor((f - T.hopStart) / len);
    const p = (f - T.hopStart - i * len) / len;
    const x0 = (HOP_X_END / HOPS) * i;
    const x1 = (HOP_X_END / HOPS) * (i + 1);
    if (p < 0.18) {
      const q = p / 0.18;
      sy = 1 - 0.22 * Math.sin(q * Math.PI * 0.5);
      x = x0;
    } else if (p < 0.82) {
      const q = (p - 0.18) / 0.64;
      x = x0 + (x1 - x0) * ease(q);
      y = Math.sin(q * Math.PI) * 0.9;
      sy = 1 + 0.18 * Math.sin(q * Math.PI);
    } else {
      const q = (p - 0.82) / 0.18;
      x = x1;
      sy = 1 - 0.2 * Math.sin(q * Math.PI);
    }
    sx = 1 / Math.sqrt(sy);
  } else if (f >= T.hopEnd) {
    x = HOP_X_END;
    // big catch jump
    if (f < T.hopEnd + 12) {
      const q = (f - T.hopEnd) / 12;
      sy = 1 - 0.3 * Math.sin(q * Math.PI * 0.5);
    } else if (f < T.land) {
      const q = (f - T.hopEnd - 12) / (T.land - T.hopEnd - 12);
      y = Math.sin(q * Math.PI) * 1.25;
      sy = 1 + 0.15 * Math.sin(q * Math.PI);
    } else if (f < T.land + 10) {
      const q = (f - T.land) / 10;
      sy = 1 - 0.22 * Math.sin(q * Math.PI);
    }
    sx = 1 / Math.sqrt(sy);
  }
  // idle breathing
  const breathe = 1 + Math.sin(f * 0.12) * 0.015;
  return { x, y, sx: sx * breathe, sy: sy / breathe };
};

// ---------- materials ----------
const useMats = () =>
  useMemo(
    () => ({
      body: new THREE.MeshPhysicalMaterial({ color: "#ff8a3d", roughness: 0.45, clearcoat: 0.4, clearcoatRoughness: 0.4 }),
      belly: new THREE.MeshStandardMaterial({ color: "#ffe2c2", roughness: 0.6 }),
      foot: new THREE.MeshStandardMaterial({ color: "#d9622b", roughness: 0.6 }),
      eyeWhite: new THREE.MeshPhysicalMaterial({ color: "#ffffff", roughness: 0.15, clearcoat: 1 }),
      iris: new THREE.MeshStandardMaterial({ color: "#3a7bd5", roughness: 0.3 }),
      pupil: new THREE.MeshStandardMaterial({ color: "#111122", roughness: 0.2 }),
      shine: new THREE.MeshBasicMaterial({ color: "#ffffff" }),
      mouth: new THREE.MeshStandardMaterial({ color: "#6b1d1d", roughness: 0.5 }),
      cheek: new THREE.MeshStandardMaterial({ color: "#ff6f91", roughness: 0.8, transparent: true, opacity: 0.55 }),
      antenna: new THREE.MeshStandardMaterial({ color: "#7a3b17", roughness: 0.5 }),
      bulb: new THREE.MeshStandardMaterial({ color: "#fff27a", emissive: "#ffd23f", emissiveIntensity: 1.2 }),
      grass: new THREE.MeshStandardMaterial({ color: "#7ccf5b", roughness: 0.95 }),
      hill1: new THREE.MeshStandardMaterial({ color: "#5fb84a", roughness: 0.95 }),
      hill2: new THREE.MeshStandardMaterial({ color: "#8fd46a", roughness: 0.95 }),
      trunk: new THREE.MeshStandardMaterial({ color: "#8a5a36", roughness: 0.8 }),
      leaf1: new THREE.MeshStandardMaterial({ color: "#3fa34d", roughness: 0.7 }),
      leaf2: new THREE.MeshStandardMaterial({ color: "#56bb5a", roughness: 0.7 }),
      cloud: new THREE.MeshStandardMaterial({ color: "#ffffff", roughness: 1 }),
      rock: new THREE.MeshStandardMaterial({ color: "#a8a29e", roughness: 0.9 }),
      star: new THREE.MeshStandardMaterial({ color: "#ffd54a", emissive: "#ffb300", emissiveIntensity: 0.9, roughness: 0.35 }),
      stem: new THREE.MeshStandardMaterial({ color: "#3d8b3d" }),
      petals: ["#ff6b9a", "#ffd23f", "#b388ff", "#ffffff"].map(
        (c) => new THREE.MeshStandardMaterial({ color: c, roughness: 0.6 }),
      ),
    }),
    [],
  );

type Mats = ReturnType<typeof useMats>;

// ---------- Golu ----------
const Golu: React.FC<{ m: Mats; star: React.ReactNode }> = ({ m, star }) => {
  const f = useCurrentFrame();
  const { x, y, sx, sy } = goluState(f);

  // eyes: closed while sleeping, then blinks
  const wakeOpen = interpolate(f, [T.wake, T.wake + 12], [0.08, 1], clamp);
  const blinks = [T.wake + 45, 420, 610, 720, 850];
  let blink = 1;
  for (const b of blinks) {
    const d = Math.abs(f - b);
    if (d < 4) blink = Math.min(blink, 0.1 + (d / 4) * 0.9);
  }
  const eyeY = wakeOpen * blink;

  // where Golu looks
  const lookStar = interpolate(f, [T.starIn + 10, T.starIn + 30], [0, 1], clamp);
  const lookBack = interpolate(f, [T.land + 10, T.hug], [0, 1], clamp);
  const pupilX = (lookStar - lookBack) * 0.05;
  const pupilY = interpolate(f, [T.starIn + 10, T.starIn + 30, T.hopStart, T.hopEnd], [0, 0.06, 0.04, 0.07], clamp) * (1 - lookBack);

  // body turn toward star while hopping, then back to camera
  const turn = interpolate(f, [T.hopStart - 20, T.hopStart, T.land + 10, T.hug], [0, 0.9, 0.9, 0], clamp);

  // arms
  const wave = f >= T.wave && f < T.starIn ? Math.sin((f - T.wave) * 0.45) * 0.6 : 0;
  const waveUp = interpolate(f, [T.wave, T.wave + 8, T.starIn - 8, T.starIn], [0, 1, 1, 0], clamp);
  const reach = interpolate(f, [T.hopEnd, T.hopEnd + 15, T.land, T.land + 10], [0, 1, 1, 0], clamp);
  const hug = interpolate(f, [T.land + 5, T.hug], [0, 1], clamp);
  const flap = f >= T.hopStart && f < T.hopEnd ? Math.sin(f * 0.6) * 0.25 : 0;

  const rightArmZ = 0.5 + waveUp * (2.0 + wave) + reach * 2.2 - hug * 0.3 + flap;
  const leftArmZ = -0.5 - reach * 2.2 + hug * 0.3 - flap;
  const armX = -hug * 1.1;

  // smile grows when star appears / caught
  const smile = interpolate(f, [T.starIn, T.starIn + 20, T.land, T.land + 15], [0.7, 0.5, 0.5, 1.1], clamp);
  const mouthOpen = interpolate(f, [T.starIn, T.starIn + 10, T.hopStart], [0, 1, 0], clamp);

  const sleepBob = f < T.wake ? Math.sin(f * 0.08) * 0.03 : 0;
  const sleepTilt = interpolate(f, [T.wake - 5, T.wake + 10], [0.18, 0], clamp);

  return (
    <group position={[x, y, 0]}>
      {/* contact shadow blob */}
      <mesh rotation={[-Math.PI / 2, 0, 0]} position={[0, 0.01 - y, 0]} scale={1 - y * 0.25}>
        <circleGeometry args={[0.55, 32]} />
        <meshBasicMaterial color="#000" transparent opacity={0.18} />
      </mesh>
      <group rotation={[0, turn, sleepTilt]} scale={[sx, sy, sx]}>
        {/* feet */}
        <mesh material={m.foot} position={[-0.25, 0.08, 0.12]} scale={[0.22, 0.1, 0.3]} castShadow>
          <sphereGeometry args={[1, 24, 16]} />
        </mesh>
        <mesh material={m.foot} position={[0.25, 0.08, 0.12]} scale={[0.22, 0.1, 0.3]} castShadow>
          <sphereGeometry args={[1, 24, 16]} />
        </mesh>
        <group position={[0, 0.72 + sleepBob, 0]}>
          {/* body */}
          <mesh material={m.body} scale={[0.66, 0.68, 0.62]} castShadow receiveShadow>
            <sphereGeometry args={[1, 48, 32]} />
          </mesh>
          <mesh material={m.belly} position={[0, -0.18, 0.34]} scale={[0.42, 0.4, 0.3]}>
            <sphereGeometry args={[1, 32, 24]} />
          </mesh>
          {/* eyes */}
          {[-1, 1].map((s) => (
            <group key={s} position={[s * 0.23, 0.2, 0.47]} scale={[1, eyeY, 1]}>
              <mesh material={m.eyeWhite} scale={[0.2, 0.24, 0.16]}>
                <sphereGeometry args={[1, 32, 24]} />
              </mesh>
              <mesh material={m.iris} position={[pupilX, pupilY, 0.13]} scale={[0.12, 0.14, 0.05]}>
                <sphereGeometry args={[1, 24, 16]} />
              </mesh>
              <mesh material={m.pupil} position={[pupilX, pupilY, 0.165]} scale={[0.07, 0.085, 0.03]}>
                <sphereGeometry args={[1, 24, 16]} />
              </mesh>
              <mesh material={m.shine} position={[pupilX + 0.04, pupilY + 0.05, 0.19]} scale={0.03}>
                <sphereGeometry args={[1, 12, 8]} />
              </mesh>
            </group>
          ))}
          {/* brows */}
          {[-1, 1].map((s) => (
            <mesh
              key={`b${s}`}
              material={m.antenna}
              position={[s * 0.23, 0.48 + mouthOpen * 0.05, 0.5]}
              rotation={[0, 0, s * (0.12 + mouthOpen * 0.1)]}
              scale={[0.12, 0.025, 0.03]}
            >
              <sphereGeometry args={[1, 16, 8]} />
            </mesh>
          ))}
          {/* cheeks */}
          {[-1, 1].map((s) => (
            <mesh key={`c${s}`} material={m.cheek} position={[s * 0.42, -0.02, 0.43]} rotation={[0, s * 0.6, 0]} scale={[0.1, 0.06, 0.02]}>
              <sphereGeometry args={[1, 16, 8]} />
            </mesh>
          ))}
          {/* mouth */}
          {mouthOpen > 0.05 ? (
            <mesh material={m.mouth} position={[0, -0.08, 0.6]} scale={[0.07, 0.09 * mouthOpen, 0.03]}>
              <sphereGeometry args={[1, 16, 12]} />
            </mesh>
          ) : (
            <mesh material={m.mouth} position={[0, -0.04, 0.58]} rotation={[0.25, 0, Math.PI]} scale={[1, smile, 1]}>
              <torusGeometry args={[0.1, 0.022, 8, 24, Math.PI]} />
            </mesh>
          )}
          {/* antenna */}
          <mesh material={m.antenna} position={[0, 0.78, 0]} rotation={[0, 0, Math.sin(f * 0.15) * 0.15]}>
            <cylinderGeometry args={[0.025, 0.03, 0.3, 8]} />
          </mesh>
          <mesh material={m.bulb} position={[Math.sin(f * 0.15) * -0.03, 0.97, 0]} scale={0.085}>
            <sphereGeometry args={[1, 16, 12]} />
          </mesh>
          {/* arms */}
          <group position={[0.6, 0.0, 0.05]} rotation={[armX, 0, rightArmZ]}>
            <mesh material={m.body} position={[0, -0.22, 0]} castShadow>
              <capsuleGeometry args={[0.09, 0.28, 8, 16]} />
            </mesh>
          </group>
          <group position={[-0.6, 0.0, 0.05]} rotation={[armX, 0, leftArmZ]}>
            <mesh material={m.body} position={[0, -0.22, 0]} castShadow>
              <capsuleGeometry args={[0.09, 0.28, 8, 16]} />
            </mesh>
          </group>
          {/* star held in hands after the catch */}
          {f >= T.catchPeak ? <group position={[0, -0.3, 0.72]} scale={0.8}>{star}</group> : null}
        </group>
      </group>
    </group>
  );
};

// ---------- Star buddy ----------
const starGeometry = (() => {
  const shape = new THREE.Shape();
  const pts = 5;
  for (let i = 0; i < pts * 2; i++) {
    const r = i % 2 === 0 ? 0.5 : 0.24;
    const a = (i / (pts * 2)) * Math.PI * 2 + Math.PI / 2;
    const px = Math.cos(a) * r;
    const py = Math.sin(a) * r;
    if (i === 0) shape.moveTo(px, py);
    else shape.lineTo(px, py);
  }
  shape.closePath();
  const g = new THREE.ExtrudeGeometry(shape, { depth: 0.12, bevelEnabled: true, bevelSize: 0.09, bevelThickness: 0.12, bevelSegments: 6 });
  g.center();
  return g;
})();

const Star: React.FC<{ m: Mats; size?: number }> = ({ m, size = 1 }) => {
  const f = useCurrentFrame();
  const glow = 1.2 + Math.sin(f * 0.2) * 0.4;
  return (
    <group scale={size} rotation={[0, 0, Math.sin(f * 0.07) * 0.15]}>
      <mesh geometry={starGeometry} material={m.star} />
      {[-1, 1].map((s) => (
        <mesh key={s} material={m.pupil} position={[s * 0.09, 0.03, 0.2]} scale={[0.035, 0.05, 0.02]}>
          <sphereGeometry args={[1, 12, 8]} />
        </mesh>
      ))}
      <mesh material={m.mouth} position={[0, -0.05, 0.2]} rotation={[0, 0, Math.PI]}>
        <torusGeometry args={[0.04, 0.012, 6, 16, Math.PI]} />
      </mesh>
      <pointLight color="#ffcc55" intensity={glow * 2.5} distance={5} decay={2} />
    </group>
  );
};

const Sparkles: React.FC<{ m: Mats }> = ({ m }) => {
  const f = useCurrentFrame();
  const t = f - T.catchPeak;
  if (t < 0) return null;
  const pop = interpolate(t, [0, 20], [0, 1], clamp);
  return (
    <group>
      {new Array(10).fill(0).map((_, i) => {
        const a = (i / 10) * Math.PI * 2 + t * 0.05;
        const r = 0.6 + pop * 0.5 + Math.sin(t * 0.1 + i) * 0.1;
        return (
          <mesh key={i} material={m.bulb} position={[Math.cos(a) * r, Math.sin(a) * r * 0.7, Math.sin(a * 2) * 0.2]} scale={0.035 * pop * (1 + Math.sin(t * 0.3 + i) * 0.4)}>
            <octahedronGeometry args={[1]} />
          </mesh>
        );
      })}
    </group>
  );
};

// ---------- environment ----------
const Tree: React.FC<{ m: Mats; p: [number, number, number]; s: number }> = ({ m, p, s }) => {
  const f = useCurrentFrame();
  const sway = Math.sin(f * 0.04 + p[0]) * 0.03;
  return (
    <group position={p} scale={s} rotation={[0, 0, sway]}>
      <mesh material={m.trunk} position={[0, 0.8, 0]} castShadow>
        <cylinderGeometry args={[0.12, 0.2, 1.6, 12]} />
      </mesh>
      <mesh material={m.leaf1} position={[0, 2.0, 0]} scale={0.9} castShadow>
        <sphereGeometry args={[1, 32, 24]} />
      </mesh>
      <mesh material={m.leaf2} position={[0.55, 1.7, 0.2]} scale={0.6} castShadow>
        <sphereGeometry args={[1, 32, 24]} />
      </mesh>
      <mesh material={m.leaf2} position={[-0.5, 1.75, -0.1]} scale={0.65} castShadow>
        <sphereGeometry args={[1, 32, 24]} />
      </mesh>
      <mesh material={m.leaf1} position={[0.1, 2.6, 0.1]} scale={0.55} castShadow>
        <sphereGeometry args={[1, 32, 24]} />
      </mesh>
    </group>
  );
};

const Flower: React.FC<{ m: Mats; p: [number, number, number]; c: number }> = ({ m, p, c }) => {
  const f = useCurrentFrame();
  return (
    <group position={p} rotation={[0, 0, Math.sin(f * 0.07 + p[0] * 3) * 0.12]}>
      <mesh material={m.stem} position={[0, 0.15, 0]}>
        <cylinderGeometry args={[0.015, 0.015, 0.3, 6]} />
      </mesh>
      {[0, 1, 2, 3, 4].map((i) => {
        const a = (i / 5) * Math.PI * 2;
        return (
          <mesh key={i} material={m.petals[c]} position={[Math.cos(a) * 0.06, 0.32, Math.sin(a) * 0.06]} scale={[0.05, 0.03, 0.05]}>
            <sphereGeometry args={[1, 12, 8]} />
          </mesh>
        );
      })}
      <mesh material={m.petals[1]} position={[0, 0.33, 0]} scale={0.035}>
        <sphereGeometry args={[1, 12, 8]} />
      </mesh>
    </group>
  );
};

const Cloud: React.FC<{ m: Mats; p: [number, number, number]; speed: number }> = ({ m, p, speed }) => {
  const f = useCurrentFrame();
  return (
    <group position={[p[0] + f * speed, p[1], p[2]]}>
      {[
        [0, 0, 0, 1],
        [1.1, -0.2, 0, 0.75],
        [-1.1, -0.25, 0, 0.7],
        [0.5, 0.4, 0, 0.7],
      ].map(([x, y, z, s], i) => (
        <mesh key={i} material={m.cloud} position={[x, y, z]} scale={[s, s * 0.8, s * 0.8]}>
          <sphereGeometry args={[1, 24, 16]} />
        </mesh>
      ))}
    </group>
  );
};

const FLOWERS: [number, number, number][] = (() => {
  const out: [number, number, number][] = [];
  let seed = 7;
  const rnd = () => ((seed = (seed * 9301 + 49297) % 233280) / 233280);
  for (let i = 0; i < 70; i++) {
    const x = -7 + rnd() * 16;
    const z = -6 + rnd() * 9;
    if (Math.abs(z) < 0.9 && x > -1 && x < 4.5) continue; // keep Golu's path clear
    out.push([x, 0, z]);
  }
  return out;
})();

// ---------- camera ----------
type Key = { f: number; pos: [number, number, number]; look: [number, number, number] };
const KEYS: Key[] = [
  { f: 0, pos: [-1, 6.5, 17], look: [0.5, 1.5, 0] },
  { f: 140, pos: [0, 1.6, 5.2], look: [0, 0.8, 0] },
  { f: 265, pos: [-0.6, 1.5, 4.6], look: [0.2, 0.95, 0] },
  { f: 330, pos: [-2.2, 2.2, 6.2], look: [1.4, 1.3, 0] },
  { f: 540, pos: [1.2, 2.0, 6.0], look: [3.3, 1.3, 0] },
  { f: 600, pos: [2.4, 1.9, 5.2], look: [3.3, 1.4, 0] },
  { f: 780, pos: [3.4, 1.5, 4.3], look: [3.2, 1.0, 0] },
  { f: 900, pos: [5.5, 4.5, 12], look: [2.5, 1.4, 0] },
];

const CameraRig: React.FC = () => {
  const f = useCurrentFrame();
  const { camera } = useThree();
  let i = 0;
  while (i < KEYS.length - 2 && f > KEYS[i + 1].f) i++;
  const a = KEYS[i];
  const b = KEYS[i + 1];
  const t = ease(Math.min(1, Math.max(0, (f - a.f) / (b.f - a.f))));
  const lerp = (u: number[], v: number[]) => u.map((n, k) => n + (v[k] - n) * t) as [number, number, number];
  const pos = lerp(a.pos, b.pos);
  const look = lerp(a.look, b.look);
  camera.position.set(...pos);
  camera.lookAt(...look);
  return null;
};

// ---------- whole scene ----------
export const GoluScene: React.FC = () => {
  const f = useCurrentFrame();
  const m = useMats();

  const sunset = interpolate(f, [T.sunsetStart, T.sunsetEnd], [0, 1], clamp);
  const sunColor = interpolateColors(sunset, [0, 1], ["#fff3dd", "#ff9a55"]);
  const skyColor = interpolateColors(sunset, [0, 1], ["#bfe6ff", "#ffb48a"]);
  const groundColor = interpolateColors(sunset, [0, 1], ["#6a8f3a", "#5a3a4a"]);
  const fogColor = interpolateColors(sunset, [0, 1], ["#d6efff", "#ffc39e"]);
  const sunH = interpolate(sunset, [0, 1], [9, 3]);

  // star path: drifts down from sky, floats, then is caught
  const starDrop = interpolate(f, [T.starIn, T.starIn + 50], [0, 1], { ...clamp, easing: Easing.out(Easing.back(1.4)) });
  const starPos: [number, number, number] = [
    STAR_FLOAT[0] + (1 - starDrop) * 2,
    STAR_FLOAT[1] + (1 - starDrop) * 5 + Math.sin(f * 0.08) * 0.12,
    STAR_FLOAT[2],
  ];

  return (
    <>
      <CameraRig />
      <fog attach="fog" args={[fogColor, 12, 34]} />
      <hemisphereLight args={[skyColor, groundColor, 0.9]} />
      <directionalLight
        position={[-6, sunH, 6]}
        color={sunColor}
        intensity={2.2}
        castShadow
        shadow-mapSize-width={2048}
        shadow-mapSize-height={2048}
        shadow-camera-left={-10}
        shadow-camera-right={10}
        shadow-camera-top={10}
        shadow-camera-bottom={-10}
        shadow-bias={-0.0005}
        shadow-radius={6}
      />
      {/* warm rim light from behind */}
      <directionalLight position={[4, 4, -8]} color="#ffd6a0" intensity={0.9} />

      {/* ground + hills */}
      <mesh material={m.grass} rotation={[-Math.PI / 2, 0, 0]} receiveShadow>
        <circleGeometry args={[40, 64]} />
      </mesh>
      <mesh material={m.hill1} position={[-9, -6.5, -14]} scale={[12, 9, 8]} receiveShadow>
        <sphereGeometry args={[1, 48, 32]} />
      </mesh>
      <mesh material={m.hill2} position={[8, -7.5, -16]} scale={[14, 10, 8]} receiveShadow>
        <sphereGeometry args={[1, 48, 32]} />
      </mesh>
      <mesh material={m.hill1} position={[20, -5, -10]} scale={[9, 7, 7]}>
        <sphereGeometry args={[1, 48, 32]} />
      </mesh>

      <Tree m={m} p={[-4.5, 0, -3]} s={1.1} />
      <Tree m={m} p={[7.5, 0, -4]} s={1.3} />
      <Tree m={m} p={[-1.5, 0, -7]} s={1.4} />
      <Tree m={m} p={[3.5, 0, -8]} s={1.2} />
      <Tree m={m} p={[-8, 0, 1]} s={1.0} />

      <mesh material={m.rock} position={[-2.2, 0.15, 1.2]} scale={[0.35, 0.22, 0.3]} castShadow>
        <sphereGeometry args={[1, 20, 14]} />
      </mesh>
      <mesh material={m.rock} position={[5.2, 0.12, 1.6]} scale={[0.28, 0.18, 0.25]} castShadow>
        <sphereGeometry args={[1, 20, 14]} />
      </mesh>

      {FLOWERS.map((p, i) => (
        <Flower key={i} m={m} p={p} c={i % 4} />
      ))}

      <Cloud m={m} p={[-12, 8, -14]} speed={0.008} />
      <Cloud m={m} p={[2, 9.5, -18]} speed={0.006} />
      <Cloud m={m} p={[12, 7.5, -12]} speed={0.01} />

      <Golu m={m} star={<><Star m={m} size={0.75} /><Sparkles m={m} /></>} />

      {f >= T.starIn - 5 && f < T.catchPeak ? (
        <group position={starPos}>
          <Star m={m} size={0.75} />
        </group>
      ) : null}
    </>
  );
};
