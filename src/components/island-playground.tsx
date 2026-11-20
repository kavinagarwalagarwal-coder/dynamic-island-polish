import { useState } from "react";
import {
  ArrowDownLeft,
  Headphones,
  MessageCircle,
  Music2,
  Pause,
  Phone,
  Play,
  Timer,
  Volume2,
} from "lucide-react";

type DemoMode = "music" | "timer" | "message" | "call" | "volume" | "split" | "handoff";

const demos: { id: DemoMode; label: string; Icon: typeof Music2 }[] = [
  { id: "music", label: "Music", Icon: Music2 },
  { id: "timer", label: "Timer", Icon: Timer },
  { id: "message", label: "Message", Icon: MessageCircle },
  { id: "call", label: "Call", Icon: Phone },
  { id: "volume", label: "Volume", Icon: Volume2 },
  { id: "split", label: "Two live", Icon: Headphones },
  { id: "handoff", label: "App handoff", Icon: ArrowDownLeft },
];

export function IslandPlayground() {
  const [mode, setMode] = useState<DemoMode>("music");
  const [isPlaying, setIsPlaying] = useState(true);
  const [motionKey, setMotionKey] = useState(0);

  function replayMotion() {
    setMotionKey((key) => key + 1);
    setIsPlaying(true);
  }

  const activeIndex = demos.findIndex((demo) => demo.id === mode) + 1;

  return (
    <section className="motion-section" id="motion" aria-labelledby="motion-title">
      <div className="section-heading motion-heading">
        <div>
          <p className="eyebrow">
            <span className="eyebrow-dot" /> MADE TO MOVE
          </p>
          <h2 id="motion-title">
            A little island.
            <br />
            <em>A lot of life.</em>
          </h2>
        </div>
        <p className="section-intro">
          We&apos;re shaping a liquid-spring feel: activities stretch, connect, and settle as they move.
        </p>
      </div>

      <div className="playground-shell">
        <div className="playground-controls" role="group" aria-label="Choose an island animation">
          {demos.map(({ id, label, Icon }) => (
            <button
              key={id}
              type="button"
              className={`demo-choice${mode === id ? " is-active" : ""}`}
              aria-pressed={mode === id}
              onClick={() => {
                setMode(id);
                replayMotion();
              }}
            >
              <Icon aria-hidden="true" />
              <span>{label}</span>
            </button>
          ))}
        </div>

        <div
          className={`playground-stage${isPlaying ? " motion-playing" : " motion-paused"}`}
          data-mode={mode}
        >
          <div className="stage-orbit stage-orbit-one" />
          <div className="stage-orbit stage-orbit-two" />
          <div className="stage-spark stage-spark-one" />
          <div className="stage-spark stage-spark-two" />
          <div className="stage-caption">
            <span>LIVE PREVIEW</span>
            <span>{String(activeIndex).padStart(2, "0")} / 07</span>
          </div>
          <div
            className="mock-phone"
            aria-label="Interactive preview of a Dynamic Droid island animation"
          >
            <div className="mock-status">
              <span>9:41</span>
              <span className="status-icons">
                <i />
                <i />
                <b />
              </span>
            </div>
            <div className="mock-wallpaper">
              <div className="wallpaper-glow" />
              <div className="demo-island-wrap" key={motionKey}>
                <div className={`demo-island mode-${mode}`}>
                  {mode === "music" && (
                    <>
                      <div className="album-art">
                        <span className="album-sun" />
                        <span className="album-horizon" />
                      </div>
                      <div className="island-copy">
                        <strong>Midnight City</strong>
                        <small>M83 · Hurry Up, We&apos;re Dreaming</small>
                      </div>
                      <div className="waveform" aria-label="Music is playing">
                        <i />
                        <i />
                        <i />
                        <i />
                        <i />
                        <i />
                        <i />
                        <i />
                        <i />
                      </div>
                    </>
                  )}
                  {mode === "timer" && (
                    <>
                      <span className="timer-ring">
                        <span />
                      </span>
                      <div className="island-copy">
                        <strong className="timer-digits">04:32</strong>
                        <small>Kitchen timer</small>
                      </div>
                      <span className="timer-status">RUNNING</span>
                    </>
                  )}
                  {mode === "message" && (
                    <>
                      <span className="message-avatar">M</span>
                      <div className="island-copy">
                        <strong>Maya Chen</strong>
                        <small>On my way — see you soon.</small>
                      </div>
                      <span className="message-dot" />
                    </>
                  )}
                  {mode === "call" && (
                    <>
                      <span className="call-icon">
                        <Phone aria-hidden="true" />
                      </span>
                      <div className="island-copy">
                        <strong>Jamie Parker</strong>
                        <small>00:24 · mobile</small>
                      </div>
                      <span className="call-wave">
                        <i />
                        <i />
                        <i />
                        <i />
                        <i />
                      </span>
                    </>
                  )}
                  {mode === "volume" && (
                    <>
                      <Volume2 className="volume-icon" aria-hidden="true" />
                      <div className="volume-control">
                        <strong>Volume</strong>
                        <span>
                          <i />
                        </span>
                      </div>
                      <b className="volume-value">72</b>
                    </>
                  )}
                  {mode === "split" && (
                    <>
                      <div className="album-art small">
                        <span className="album-sun" />
                        <span className="album-horizon" />
                      </div>
                      <div className="island-copy">
                        <strong>Midnight City</strong>
                        <small>M83 · Music playing</small>
                      </div>
                    </>
                  )}
                  {mode === "handoff" && (
                    <>
                      <span className="handoff-app">
                        <span className="app-glyph">M</span>
                      </span>
                      <div className="island-copy">
                        <strong>Coming back to life</strong>
                        <small>App → island handoff</small>
                      </div>
                      <span className="handoff-arrow">↗</span>
                    </>
                  )}
                </div>
              </div>
              {mode === "handoff" && (
                <div className="handoff-card">
                  <span className="handoff-card-art" />
                  <span>
                    <b>Now playing</b>
                    <small>Falling into Place</small>
                  </span>
                </div>
              )}
              <div className="mock-home-row">
                <span />
                <span />
                <span />
                <span />
              </div>
              <div className="mock-dock">
                <i />
                <i />
                <i />
                <i />
              </div>
            </div>
            <div className="mock-home-indicator" />
          </div>
          {mode === "split" && (
            <div
              className="floating-bubble"
              key={motionKey}
              role="img"
              aria-label="Attached timer activity, 4 minutes 32 seconds"
            >
              <span>4:32</span>
            </div>
          )}
          <div className="stage-note">
            <span className="stage-note-dot" /> Liquid springs · fluid morphs, still in motion
          </div>
        </div>

        <div className="playground-footer">
          <div className="playback-label">
            <span className="live-indicator" />{" "}
            <span>
              {mode === "handoff"
                ? "APP → ISLAND"
                : mode === "split"
                  ? "TWO ACTIVITIES, ONE MOMENT"
                  : `${mode.toUpperCase()} ISLAND`}
            </span>
          </div>
          <button
            className="replay-button"
            type="button"
            onClick={() => (isPlaying ? setIsPlaying(false) : replayMotion())}
            aria-pressed={isPlaying}
          >
            {isPlaying ? <Pause aria-hidden="true" /> : <Play aria-hidden="true" />}
            <span>{isPlaying ? "Pause motion" : "Replay motion"}</span>
          </button>
          <p className="playground-footnote">An in-progress look at our liquid-spring motion.</p>
        </div>
      </div>
    </section>
  );
}
