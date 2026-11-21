import { useThree } from "@react-three/fiber";
import React, { useMemo } from "react";
import { interpolate, interpolateColors, spring, useCurrentFrame, useVideoConfig } from "remotion";
import * as THREE from "three";

const box = new THREE.BoxGeometry(1, 1, 1);

const N = 14;
const HALF = N / 2;

export const heightAt = (x: number, z: number) => {
  const h = 2 + 1.6 * Math.sin(x * 0.55) + 1.3 * Math.cos(z * 0.6 + 1);
  return Math.max(1, Math.min(5, Math.round(h)));
};

const mats = {
  grass: new THREE.MeshLambertMaterial({ color: "#5bc236" }),
  grassDark: new THREE.MeshLambertMaterial({ color: "#4aa52b" }),
  dirt: new THREE.MeshLambertMaterial({ color: "#8b5a2b" }),
  sand: new THREE.MeshLambertMaterial({ color: "#e8d48a" }),
  water: new THREE.MeshLambertMaterial({ color: "#3fa9f5", transparent: true, opacity: 0.85 }),
  trunk: new THREE.MeshLambertMaterial({ color: "#6b4226" }),
  leaves: new THREE.MeshLambertMaterial({ color: "#2e8b3a" }),
  cloud: new THREE.MeshLambertMaterial({ color: "#ffffff" }),
  sun: new THREE.MeshBasicMaterial({ color: "#ffe066" }),
  skin: new THREE.MeshLambertMaterial({ color: "#f2c18d" }),
  shirt: new THREE.MeshLambertMaterial({ color: "#e63946" }),
  pants: new THREE.MeshLambertMaterial({ color: "#264de4" }),
  hair: new THREE.MeshLambertMaterial({ color: "#3b2416" }),
  eye: new THREE.MeshBasicMaterial({ color: "#111111" }),
};

type Block = { x: number; y: number; z: number; mat: THREE.Material; col: number };

const useTerrain = () =>
  useMemo(() => {
    const blocks: Block[] = [];
    let col = 0;
    for (let x = -HALF; x < HALF; x++) {
      for (let z = -HALF; z < HALF; z++) {
        const h = heightAt(x, z);
        if (h <= 1) {
          blocks.push({ x, y: 0, z, mat: mats.sand, col });
          blocks.push({ x, y: 1, z, mat: mats.water, col });
        } else {
          for (let y = Math.max(0, h - 2); y < h; y++) {
            blocks.push({ x, y, z, mat: mats.dirt, col });
          }
          blocks.push({ x, y: h, z, mat: (x + z) % 2 === 0 ? mats.grass : mats.grassDark, col });
        }
        col++;
      }
    }
    return blocks;
  }, []);

const TREES: [number, number][] = [
  [-5, -4],
  [4, -5],
  [-3, 4],
  [5, 3],
  [0, -6],
  [-6, 2],
];

const Tree: React.FC<{ x: number; z: number; delay: number }> = ({ x, z, delay }) => {
  const frame = useCurrentFrame();
  const { fps } = useVideoConfig();
  const s = spring({ frame: frame - delay, fps, config: { damping: 9 } });
  const base = heightAt(x, z) + 1;
  const leaves: [number, number, number][] = [];
  for (let dx = -1; dx <= 1; dx++)
    for (let dz = -1; dz <= 1; dz++) {
      leaves.push([dx, 2, dz]);
      if (Math.abs(dx) + Math.abs(dz) <= 1) leaves.push([dx, 3, dz]);
    }
  leaves.push([0, 4, 0]);
  return (
    <group position={[x, base - 0.5, z]} scale={[s, s, s]}>
      <mesh geometry={box} material={mats.trunk} position={[0, 0.5, 0]} />
      <mesh geometry={box} material={mats.trunk} position={[0, 1.5, 0]} />
      {leaves.map(([lx, ly, lz], i) => (
        <mesh key={i} geometry={box} material={mats.leaves} position={[lx, ly + 0.5, lz]} />
      ))}
    </group>
  );
};

const Cloud: React.FC<{ y: number; z: number; offset: number; speed: number }> = ({ y, z, offset, speed }) => {
  const frame = useCurrentFrame();
  const x = ((offset + frame * speed + 20) % 40) - 20;
  const parts: [number, number, number][] = [
    [0, 0, 0],
    [1, 0, 0],
    [2, 0, 0],
    [1, 0, 1],
    [-1, 0, 0],
    [0, 0, 1],
    [1, 1, 0],
  ];
  return (
    <group position={[x, y, z]}>
      {parts.map(([px, py, pz], i) => (
        <mesh key={i} geometry={box} material={mats.cloud} position={[px, py, pz]} />
      ))}
    </group>
  );
};

const Hero: React.FC<{ start: number }> = ({ start }) => {
  const frame = useCurrentFrame();
  const { fps } = useVideoConfig();
  const t = Math.max(0, frame - start);
  const appear = spring({ frame: frame - start, fps, config: { damping: 10 } });
  const angle = t * 0.03;
  const r = 3.5;
  const x = Math.cos(angle) * r;
  const z = Math.sin(angle) * r;
  const ground = heightAt(Math.round(x), Math.round(z));
  const hop = Math.abs(Math.sin(t * 0.25)) * 0.8;
  const swing = Math.sin(t * 0.5) * 0.6;
  const drop = interpolate(appear, [0, 1], [10, 0]);
  if (frame < start) return null;
  return (
    <group position={[x, ground + 0.5 + hop + drop, z]} rotation={[0, -angle, 0]} scale={0.55}>
      {/* legs */}
      <mesh geometry={box} material={mats.pants} position={[0, 0.75, -0.3]} scale={[0.5, 1.5, 0.5]} rotation={[0, 0, swing]} />
      <mesh geometry={box} material={mats.pants} position={[0, 0.75, 0.3]} scale={[0.5, 1.5, 0.5]} rotation={[0, 0, -swing]} />
      {/* body */}
      <mesh geometry={box} material={mats.shirt} position={[0, 2.25, 0]} scale={[0.7, 1.5, 1.2]} />
      {/* arms */}
      <mesh geometry={box} material={mats.skin} position={[0, 2.25, -0.85]} scale={[0.45, 1.4, 0.45]} rotation={[0, 0, -swing]} />
      <mesh geometry={box} material={mats.skin} position={[0, 2.25, 0.85]} scale={[0.45, 1.4, 0.45]} rotation={[0, 0, swing]} />
      {/* head */}
      <mesh geometry={box} material={mats.skin} position={[0, 3.55, 0]} scale={[1.1, 1.1, 1.1]} />
      <mesh geometry={box} material={mats.hair} position={[0, 4.15, 0]} scale={[1.15, 0.25, 1.15]} />
      <mesh geometry={box} material={mats.eye} position={[0.56, 3.65, -0.25]} scale={[0.05, 0.2, 0.18]} />
      <mesh geometry={box} material={mats.eye} position={[0.56, 3.65, 0.25]} scale={[0.05, 0.2, 0.18]} />
    </group>
  );
};

const CameraRig: React.FC<{ zoom: number; lookY: number }> = ({ zoom, lookY }) => {
  const frame = useCurrentFrame();
  const { camera } = useThree();
  const angle = 0.7 + frame * 0.006;
  const radius = interpolate(frame, [0, 120, 900], [52, 42, 36], { extrapolateRight: "clamp" }) * zoom;
  const height = interpolate(frame, [0, 120, 900], [34, 26, 22], { extrapolateRight: "clamp" }) * zoom;
  camera.position.set(Math.cos(angle) * radius, height, Math.sin(angle) * radius);
  camera.lookAt(0, lookY, 0);
  return null;
};

export const World: React.FC<{ zoom?: number; lookY?: number }> = ({ zoom = 1, lookY = 4 }) => {
  const frame = useCurrentFrame();
  const { fps } = useVideoConfig();
  const blocks = useTerrain();

  // day -> sunset lighting
  const sunsetT = interpolate(frame, [600, 780], [0, 1], { extrapolateLeft: "clamp", extrapolateRight: "clamp" });
  const lightColor = interpolateColors(sunsetT, [0, 1], ["#ffffff", "#ff9a4d"]);
  const ambient = interpolate(sunsetT, [0, 1], [0.75, 0.6]);
  const sunAngle = interpolate(sunsetT, [0, 1], [1.1, 0.15]);
  const sunPos: [number, number, number] = [Math.cos(sunAngle) * -22, Math.sin(sunAngle) * 22, -10];

  return (
    <>
      <CameraRig zoom={zoom} lookY={lookY} />
      <ambientLight intensity={ambient} color={lightColor} />
      <directionalLight position={sunPos} intensity={1.6} color={lightColor} />
      <hemisphereLight args={["#bfe6ff", "#5a3d1e", 0.3]} />

      <mesh geometry={box} material={mats.sun} position={sunPos} scale={3} />

      {blocks.map((b, i) => {
        const cx = (b.col / N) | 0;
        const cz = b.col % N;
        const dist = Math.hypot(cx - HALF, cz - HALF);
        const s = spring({ frame: frame - dist * 6 - 10, fps, config: { damping: 14, stiffness: 120 } });
        const y = b.y + interpolate(s, [0, 1], [14, 0]);
        if (s < 0.001) return null;
        return <mesh key={i} geometry={box} material={b.mat} position={[b.x, y, b.z]} />;
      })}

      {TREES.map(([x, z], i) => (
        <Tree key={i} x={x} z={z} delay={140 + i * 8} />
      ))}

      <Cloud y={11} z={-3} offset={0} speed={0.04} />
      <Cloud y={12} z={4} offset={18} speed={0.03} />
      <Cloud y={10} z={0} offset={30} speed={0.05} />

      <Hero start={330} />
    </>
  );
};
