import { loadFont } from "@remotion/fonts";
import { ThreeCanvas } from "@remotion/three";
import React from "react";
import {
  AbsoluteFill,
  Composition,
  interpolate,
  interpolateColors,
  Sequence,
  spring,
  staticFile,
  useCurrentFrame,
  useVideoConfig,
} from "remotion";
import * as THREE from "three";
import { GoluScene, T } from "./Scene";

const fontFamily = "Fredoka";
loadFont({ family: fontFamily, url: staticFile("Fredoka.woff2"), weight: "600" });

export const GoluComposition: React.FC = () => (
  <Composition id="GoluWide" component={GoluVideo} durationInFrames={900} fps={30} width={1920} height={1080} />
);

const clamp = { extrapolateLeft: "clamp", extrapolateRight: "clamp" } as const;

const Caption: React.FC<{ text: string; title?: boolean; sub?: string }> = ({ text, title, sub }) => {
  const f = useCurrentFrame();
  const { fps, durationInFrames } = useVideoConfig();
  const pop = spring({ frame: f, fps, config: { damping: 12, stiffness: 140 } });
  const out = interpolate(f, [durationInFrames - 12, durationInFrames], [1, 0], clamp);
  return (
    <AbsoluteFill
      style={{
        justifyContent: title ? "flex-start" : "flex-end",
        alignItems: "center",
        paddingTop: title ? 110 : 0,
        paddingBottom: title ? 0 : 80,
        opacity: out,
      }}
    >
      <div
        style={{
          fontFamily,
          fontWeight: 600,
          fontSize: title ? 130 : 64,
          color: title ? "#fff8e7" : "#ffffff",
          textAlign: "center",
          letterSpacing: title ? 2 : 0.5,
          textShadow: title
            ? "0 6px 0 #d9622b, 0 14px 30px rgba(0,0,0,0.35)"
            : "0 3px 0 rgba(0,0,0,0.35), 0 6px 22px rgba(0,0,0,0.45)",
          transform: `scale(${0.6 + pop * 0.4}) translateY(${(1 - pop) * 30}px)`,
          padding: "0 120px",
        }}
      >
        {text}
      </div>
      {sub ? (
        <div
          style={{
            fontFamily,
            fontWeight: 600,
            fontSize: 44,
            color: "#ffe7a3",
            marginTop: 16,
            textShadow: "0 3px 12px rgba(0,0,0,0.4)",
            opacity: interpolate(f, [12, 26], [0, 1], clamp),
          }}
        >
          {sub}
        </div>
      ) : null}
    </AbsoluteFill>
  );
};

export const GoluVideo: React.FC = () => {
  const f = useCurrentFrame();
  const { width, height } = useVideoConfig();
  const sunset = interpolate(f, [T.sunsetStart, T.sunsetEnd], [0, 1], clamp);
  const top = interpolateColors(sunset, [0, 1], ["#5fb3ff", "#6a4c93"]);
  const mid = interpolateColors(sunset, [0, 1], ["#a8dcff", "#f78f8f"]);
  const bottom = interpolateColors(sunset, [0, 1], ["#e6f6ff", "#ffc98b"]);
  const fade = interpolate(f, [0, 15, 880, 900], [0, 1, 1, 0], clamp);

  return (
    <AbsoluteFill style={{ background: "#000" }}>
      <AbsoluteFill style={{ opacity: fade, background: `linear-gradient(${top}, ${mid} 55%, ${bottom})` }}>
        <ThreeCanvas
          width={width}
          height={height}
          dpr={1}
          shadows={{ type: THREE.PCFSoftShadowMap }}
          camera={{ fov: 35, near: 0.1, far: 100 }}
          gl={{ antialias: true, toneMapping: THREE.ACESFilmicToneMapping, toneMappingExposure: 1.05 }}
        >
          <GoluScene />
        </ThreeCanvas>
        {/* soft vignette for a filmic look */}
        <AbsoluteFill style={{ background: "radial-gradient(ellipse at center, transparent 55%, rgba(0,0,0,0.35) 100%)" }} />

        <Sequence from={10} durationInFrames={130}>
          <Caption title text="Golu ka Sapna" sub="ek chhoti si kahani" />
        </Sequence>
        <Sequence from={T.wake + 15} durationInFrames={T.starIn - T.wake - 15}>
          <Caption text="Ye hai Golu. Hello dosto!" />
        </Sequence>
        <Sequence from={T.starIn + 15} durationInFrames={T.hopStart + 70 - T.starIn - 15}>
          <Caption text="Arre! Ek chamakta tara?" />
        </Sequence>
        <Sequence from={T.hopStart + 80} durationInFrames={T.hopEnd - T.hopStart - 80}>
          <Caption text="Ruk ja... main aa raha hoon!" />
        </Sequence>
        <Sequence from={T.land} durationInFrames={T.outro - T.land}>
          <Caption text="Pakad liya! Ab hum dost hain." />
        </Sequence>
        <Sequence from={T.outro} durationInFrames={900 - T.outro}>
          <Caption title text="The End" sub="Made with Remotion + Claude" />
        </Sequence>
      </AbsoluteFill>
    </AbsoluteFill>
  );
};
