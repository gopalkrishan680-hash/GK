import { loadFont } from "@remotion/fonts";
import { ThreeCanvas } from "@remotion/three";
import React from "react";
import {
  AbsoluteFill,
  Composition,
  interpolate,
  interpolateColors,
  Sequence,
  staticFile,
  spring,
  useCurrentFrame,
  useVideoConfig,
} from "remotion";
import { World } from "./World";

const fontFamily = "PressStart2P";
loadFont({ family: fontFamily, url: staticFile("PressStart2P.woff2") });

// Render the 3D scene at low resolution, then upscale with nearest-neighbour for a true pixel look.
const PIXEL = 3;

export const MyComposition = () => {
  return (
    <>
      <Composition id="PixelWorld" component={MyComponent} durationInFrames={900} fps={30} width={1080} height={1920} />
      <Composition id="PixelWorldWide" component={MyComponent} durationInFrames={900} fps={30} width={1920} height={1080} />
    </>
  );
};

const Caption: React.FC<{ title: string; sub?: string; big?: boolean }> = ({ title, sub, big }) => {
  const frame = useCurrentFrame();
  const { fps, durationInFrames, width, height } = useVideoConfig();
  const wide = width > height;
  const pop = spring({ frame, fps, config: { damping: 11 } });
  const out = interpolate(frame, [durationInFrames - 12, durationInFrames], [1, 0], {
    extrapolateLeft: "clamp",
    extrapolateRight: "clamp",
  });
  const shadow = "6px 6px 0 #1b1b2f, -2px -2px 0 #1b1b2f, 2px -2px 0 #1b1b2f, -2px 2px 0 #1b1b2f";
  return (
    <AbsoluteFill style={{ alignItems: "center", paddingTop: wide ? (big ? 120 : 70) : big ? 360 : 220, opacity: out }}>
      <div
        style={{
          fontFamily,
          color: "#fff",
          fontSize: wide ? (big ? 96 : 56) : big ? 104 : 64,
          lineHeight: 1.35,
          textAlign: "center",
          textShadow: shadow,
          transform: `scale(${pop}) translateY(${(1 - pop) * -80}px)`,
          padding: "0 70px",
        }}
      >
        {title}
      </div>
      {sub ? (
        <div
          style={{
            fontFamily,
            marginTop: wide ? 24 : 40,
            fontSize: 36,
            color: "#ffe066",
            textShadow: "4px 4px 0 #1b1b2f",
            textAlign: "center",
            padding: "0 70px",
            lineHeight: 1.6,
            opacity: interpolate(frame, [10, 25], [0, 1], { extrapolateLeft: "clamp", extrapolateRight: "clamp" }),
          }}
        >
          {sub}
        </div>
      ) : null}
    </AbsoluteFill>
  );
};

export const MyComponent: React.FC = () => {
  const frame = useCurrentFrame();
  const { width: W, height: H } = useVideoConfig();
  const wide = W > H;
  const sunsetT = interpolate(frame, [600, 780], [0, 1], { extrapolateLeft: "clamp", extrapolateRight: "clamp" });
  const skyTop = interpolateColors(sunsetT, [0, 1], ["#4fa3ff", "#3a1c71"]);
  const skyBottom = interpolateColors(sunsetT, [0, 1], ["#bfe9ff", "#ff8a4c"]);
  const fadeIn = interpolate(frame, [0, 15], [0, 1], { extrapolateRight: "clamp" });

  return (
    <AbsoluteFill style={{ background: `linear-gradient(${skyTop}, ${skyBottom})` }}>
      <style>{`canvas { image-rendering: pixelated; }`}</style>
      <AbsoluteFill style={{ opacity: fadeIn }}>
        <div style={{ width: W / PIXEL, height: H / PIXEL, transform: `scale(${PIXEL})`, transformOrigin: "top left" }}>
          <ThreeCanvas
            width={W / PIXEL}
            height={H / PIXEL}
            dpr={1}
            camera={{ fov: 40, position: [30, 20, 30] }}
            gl={{ antialias: false }}
          >
            <World zoom={wide ? 0.62 : 1} lookY={wide ? 3.5 : 4} />
          </ThreeCanvas>
        </div>
      </AbsoluteFill>

      <Sequence from={0} durationInFrames={135}>
        <Caption big title="PIXEL WORLD" sub="3D VOXEL DEMO" />
      </Sequence>
      <Sequence from={150} durationInFrames={170}>
        <Caption title="Block by block duniya bani" sub="ped, paani aur baadal" />
      </Sequence>
      <Sequence from={335} durationInFrames={250}>
        <Caption title="Hero ki entry!" sub="chalo ghoomne chalein" />
      </Sequence>
      <Sequence from={600} durationInFrames={180}>
        <Caption title="Sunset mode" sub="din se shaam tak" />
      </Sequence>
      <Sequence from={790} durationInFrames={110}>
        <Caption big title="THE END" sub="Made with Remotion + Claude" />
      </Sequence>
    </AbsoluteFill>
  );
};
