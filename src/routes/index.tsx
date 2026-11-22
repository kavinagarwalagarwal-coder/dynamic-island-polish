import {
  ArrowDownRight,
  ArrowUpRight,
  Check,
  CircleDot,
  Fingerprint,
  MoveUpRight,
  Waves,
} from "lucide-react";
import { Link, createFileRoute } from "@tanstack/react-router";
import { IslandPlayground } from "@/components/island-playground";
import { SiteHeader } from "@/components/site-header";

const featureCards = [
  {
    number: "01",
    title: "Music, in motion",
    description: "Artwork, controls and a beat that feels right at home.",
    image: "/island-glow-music.png",
    alt: "Dynamic Droid music island with a soft edge glow",
    tone: "tone-lime",
  },
  {
    number: "02",
    title: "Messages that land",
    description: "See what matters. Reply without leaving the moment.",
    image: "/island-message.png",
    alt: "Dynamic Droid message island with reply actions",
    tone: "tone-violet",
  },
  {
    number: "03",
    title: "Time that stays live",
    description: "A real countdown, with pause, resume and cancel built in.",
    image: "/island-timer.png",
    alt: "Dynamic Droid timer island with a live countdown",
    tone: "tone-blue",
  },
  {
    number: "04",
    title: "Two things at once",
    description: "A second activity peels away into its own little bubble.",
    image: "/island-split.png",
    alt: "Music and a detached timer bubble shown together",
    tone: "tone-pink",
  },
  {
    number: "05",
    title: "Liquid Glass",
    description: "A still slice of your wallpaper, refracted through the island.",
    image: "/island-glass.png",
    alt: "Expanded Dynamic Droid island with a Liquid Glass wallpaper skin",
    tone: "tone-cyan",
  },
  {
    number: "06",
    title: "Volume, reimagined",
    description: "A familiar slider, lifted into the place you are already looking.",
    image: "/island-volume.png",
    alt: "Dynamic Droid volume island with a level bar",
    tone: "tone-orange",
  },
];

export const Route = createFileRoute("/")({
  head: () => ({
    meta: [
      { title: "Dynamic Droid — Your camera hole, now alive" },
      {
        name: "description",
        content:
          "A native Android island for music, calls, messages, timers and more — with smooth, spring-driven animations.",
      },
      { property: "og:title", content: "Dynamic Droid — Your camera hole, now alive" },
      {
        property: "og:description",
        content: "Music, calls, messages and live activities with a little more life.",
      },
      { property: "og:type", content: "website" },
      { name: "twitter:card", content: "summary_large_image" },
    ],
  }),
  component: Index,
});

function Index() {
  return (
    <main className="site-page">
      <SiteHeader />
      <section className="hero-section">
        <div className="hero-copy">
          <p className="eyebrow">
            <span className="eyebrow-dot" /> THE ANDROID ISLAND, REIMAGINED
          </p>
          <h1>
            Your camera hole,
            <br />
            <em>now alive.</em>
          </h1>
          <p className="hero-description">
            The little things on your phone deserve a little more life. Music, messages, timers and
            more — all flowing from one tiny place.
          </p>
          <div className="hero-actions">
            <Link to="/releases" className="button-primary">
              Get the latest <ArrowUpRight aria-hidden="true" />
            </Link>
            <a href="#motion" className="button-quiet">
              Feel the motion <ArrowDownRight aria-hidden="true" />
            </a>
          </div>
          <div className="hero-proof">
            <span>
              <Check aria-hidden="true" /> Android 8.0+
            </span>
            <span>
              <Fingerprint aria-hidden="true" /> No tracking
            </span>
          </div>
        </div>
        <div
          className="hero-art"
          aria-label="Dynamic Droid's camera island animates out of a phone's punch-hole camera"
        >
          <div className="hero-ambient ambient-one" />
          <div className="hero-ambient ambient-two" />
          <div className="hero-grid" />
          <div className="hero-coordinate coordinate-top">
            40° 43&apos; 55.3&quot; N <span>·</span> 73° 59&apos; 11.6&quot; W
          </div>
          <div className="hero-phone">
            <div className="hero-phone-top">
              <span>9:41</span>
              <span className="hero-status-icons">
                <i />
                <i />
                <b />
              </span>
            </div>
            <div className="hero-static-island">
              <div className="hero-camera" />
              <div className="hero-island-event">
                <span className="hero-mini-art">
                  <i />
                </span>
                <span className="hero-mini-copy">
                  <b>Night Drive</b>
                  <small>Chromatics</small>
                </span>
                <span className="hero-wave">
                  <i />
                  <i />
                  <i />
                  <i />
                  <i />
                  <i />
                </span>
              </div>
            </div>
            <div className="hero-clock">
              <span>Good evening</span>
              <strong>8:42</strong>
              <small>MONDAY, NOVEMBER 16</small>
            </div>
            <div className="hero-widget">
              <div className="widget-weather">
                <span className="weather-sun" />
                <b>72°</b>
                <small>Clear sky</small>
              </div>
              <div className="widget-note">
                <span>UP NEXT</span>
                <strong>
                  Make room
                  <br />
                  for wonder.
                </strong>
                <small>2 reminders</small>
              </div>
            </div>
            <div className="hero-app-grid">
              <i />
              <i />
              <i />
              <i />
            </div>
            <div className="hero-dock">
              <i />
              <i />
              <i />
              <i />
            </div>
            <div className="hero-home-indicator" />
          </div>
          <div className="hero-float-note note-left">
            <span className="note-spark">✳</span>
            <span>
              SPRING-DRIVEN
              <br />
              <b>Never skips a beat</b>
            </span>
          </div>
          <div className="hero-float-note note-right">
            <span className="note-pulse">
              <CircleDot aria-hidden="true" />
            </span>
            <span>
              AT A GLANCE
              <br />
              <b>One place. More life.</b>
            </span>
          </div>
          <div className="hero-coordinate coordinate-bottom">
            NATIVE ANDROID <span>·</span> BUILT TO MOVE
          </div>
        </div>
        <a href="#features" className="scroll-cue">
          <span className="scroll-cue-line" /> SCROLL TO FEEL IT
        </a>
      </section>

      <div className="ticker" aria-label="Dynamic Droid features">
        <div className="ticker-track">
          <span>NOTIFICATIONS, BUT ALIVE</span>
          <b>✳</b>
          <span>SPRINGS THAT FEEL RIGHT</span>
          <b>✳</b>
          <span>MADE FOR ANDROID</span>
          <b>✳</b>
          <span>NOTIFICATIONS, BUT ALIVE</span>
          <b>✳</b>
          <span>SPRINGS THAT FEEL RIGHT</span>
          <b>✳</b>
          <span>MADE FOR ANDROID</span>
          <b>✳</b>
        </div>
      </div>

      <IslandPlayground />

      <section className="features-section" id="features" aria-labelledby="features-title">
        <div className="section-heading feature-heading">
          <div>
            <p className="eyebrow">
              <span className="eyebrow-dot" /> THE EVERYDAY, ELEVATED
            </p>
            <h2 id="features-title">
              Small surface.
              <br />
              <em>Big energy.</em>
            </h2>
          </div>
          <p className="section-intro">
            All the things you already do on your phone, rethought as one fluid little layer. No
            extra noise. Just the right thing, right there.
          </p>
        </div>
        <div className="feature-grid">
          {featureCards.map((feature) => (
            <article className={`feature-card ${feature.tone}`} key={feature.number}>
              <div className="feature-image-wrap">
                <img src={feature.image} alt={feature.alt} loading="lazy" />
                <span className="feature-number">{feature.number}</span>
                <span className="feature-image-glow" />
              </div>
              <div className="feature-card-copy">
                <div>
                  <h3>{feature.title}</h3>
                  <p>{feature.description}</p>
                </div>
                <MoveUpRight aria-hidden="true" />
              </div>
            </article>
          ))}
        </div>
        <div className="features-more">
          <span>
            <Waves aria-hidden="true" /> AND ALSO
          </span>
          <p>
            Calls · alarms · downloads · headphones · ringer modes · Do Not Disturb · live wallpaper
            · dot music
          </p>
        </div>
      </section>

      <section className="privacy-section">
        <div className="privacy-symbol">
          <span />
          <i />
        </div>
        <div className="privacy-copy">
          <p className="eyebrow">
            <span className="eyebrow-dot" /> YOUR PHONE. YOUR BUSINESS.
          </p>
          <h2>
            Alive on the surface.
            <br />
            <em>Private underneath.</em>
          </h2>
          <p>
            No account and no tracking. Notifications stay on your phone; network access is used
            only for the optional live cricket score feed.
          </p>
          <Link to="/releases" className="text-link">
            Meet the latest release <ArrowUpRight aria-hidden="true" />
          </Link>
        </div>
        <div className="privacy-stamp">
          <span>
            OFFLINE
            <br />
            BY DESIGN
          </span>
          <Fingerprint aria-hidden="true" />
        </div>
      </section>

      <section className="final-cta">
        <div className="final-cta-orb" />
        <p className="eyebrow">
          <span className="eyebrow-dot" /> MAKE YOUR PHONE FEEL A LITTLE MORE ALIVE
        </p>
        <h2>
          It starts with
          <br />
          <em>a little dot.</em>
        </h2>
        <Link to="/releases" className="button-primary">
          Explore latest releases <ArrowUpRight aria-hidden="true" />
        </Link>
        <span className="cta-footnote">Free · Open source · Android 8.0+</span>
      </section>

      <footer className="site-footer">
        <Link to="/" className="brand-lockup">
          <span className="brand-mark" aria-hidden="true">
            <span />
          </span>
          <span className="brand-name">
            dynamic<span>droid</span>
          </span>
        </Link>
        <span>Built for the little moments.</span>
        <Link to="/releases">
          Latest releases <ArrowUpRight aria-hidden="true" />
        </Link>
      </footer>
    </main>
  );
}
