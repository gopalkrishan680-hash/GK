import { MyComposition } from "./Composition";
import { GoluComposition } from "./golu/GoluVideo";

export const RemotionRoot: React.FC = () => {
  return (
    <>
      <GoluComposition />
      <MyComposition />
    </>
  );
};
