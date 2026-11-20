import {
  ArrowLeft,
  ArrowUpRight,
  Check,
  Download,
  Github,
  ShieldCheck,
  Smartphone,
} from "lucide-react";
import { Link, createFileRoute } from "@tanstack/react-router";
import { SiteHeader } from "@/components/site-header";

const releaseUrl =
  "https://github.com/kavinagarwalagarwal-coder/dynamic-island-polish/releases/tag/Dynamic-island-v2";
const downloadUrl =
  "https://github.com/kavinagarwalagarwal-coder/dynamic-island-polish/releases/download/Dynamic-island-v2/dynamic-droid-optimized-apk.2.zip";

export const Route = createFileRoute("/releases")({
  head: () => ({
    meta: [
      { title: "Latest releases — Dynamic Droid" },
      {
        name: "description",
        content: "Get the latest Dynamic Droid Android release and explore what is new.",
      },
      { property: "og:title", content: "Latest releases — Dynamic Droid" },
      { property: "og:description", content: "Get the latest Dynamic Droid Android release." },
    ],
  }),
  component: ReleasesPage,
});

function ReleasesPage() {
  return (
    <main className="site-page releases-page">
      <SiteHeader />
      <section className="release-hero">
        <div className="release-hero-copy">
          <Link to="/" className="back-link">
            <ArrowLeft aria-hidden="true" /> Back to Dynamic Droid
          </Link>
          <p className="eyebrow">
            <span className="eyebrow-dot" /> THE RELEASE SHELF
          </p>
          <h1>
            Fresh from
            <br />
            <em>the island.</em>
          </h1>
          <p className="release-lede">
            Every new motion, polish pass, and tiny detail — right here. Pick up the latest build
            and make your camera cutout come alive.
          </p>
        </div>
        <div className="release-mark" aria-hidden="true">
          <div className="release-mark-ring ring-a" />
          <div className="release-mark-ring ring-b" />
          <div className="release-mark-pill">
            <span />
          </div>
          <span className="release-mark-star star-a">✳</span>
          <span className="release-mark-star star-b">✳</span>
          <span className="release-mark-orbit">DYNAMIC / ANDROID</span>
        </div>
      </section>

      <section className="release-content" aria-labelledby="latest-release-title">
        <div className="release-list-heading">
          <div>
            <span className="release-kicker">LATEST BUILD</span>
            <span className="release-live">
              <i /> AVAILABLE NOW
            </span>
          </div>
          <span className="release-index">01 / 01</span>
        </div>
        <article className="release-card">
          <div className="release-card-top">
            <div className="release-version">
              <span className="version-chip">V2</span>
              <span className="release-date">NOVEMBER 02, 2026</span>
            </div>
            <a
              href={releaseUrl}
              target="_blank"
              rel="noreferrer"
              className="release-github"
              aria-label="Read release notes on GitHub"
            >
              GITHUB RELEASE <ArrowUpRight aria-hidden="true" />
            </a>
          </div>
          <div className="release-main">
            <div className="release-title-block">
              <h2 id="latest-release-title">
                Dynamic Droid <em>v2</em>
              </h2>
              <p>More life, right where your camera lives.</p>
            </div>
            <div className="release-whats-new">
              <span className="release-kicker">FEATURES IN THE APP</span>
              <p>
                <Check aria-hidden="true" /> A more expressive island experience for Android
              </p>
              <p>
                <Check aria-hidden="true" /> Native animations for live activities and everyday
                moments
              </p>
              <p>
                <Check aria-hidden="true" /> A polished, privacy-first build with no internet
                permission
              </p>
            </div>
          </div>
          <div className="release-card-bottom">
            <div className="release-requirement">
              <span>
                <Smartphone aria-hidden="true" /> Android 8.0+
              </span>
              <span>
                <ShieldCheck aria-hidden="true" /> Private by design
              </span>
              <span>
                <Download aria-hidden="true" /> Optimized APK package
              </span>
            </div>
            <a className="download-button" href={downloadUrl} target="_blank" rel="noreferrer">
              <Download aria-hidden="true" /> Download latest
            </a>
          </div>
        </article>
        <div className="release-note">
          <span className="release-note-bullet">01</span>
          <p>
            Installs over your current build and keeps your settings. On Android 16 or 17, check the
            release page for the dedicated compatibility build.
          </p>
          <a href={releaseUrl} target="_blank" rel="noreferrer">
            See every release <ArrowUpRight aria-hidden="true" />
          </a>
        </div>
      </section>

      <section className="release-cta">
        <div>
          <span className="eyebrow">
            <span className="eyebrow-dot" /> KEEP THE MOMENT MOVING
          </span>
          <h2>
            Next up: <em>your phone.</em>
          </h2>
        </div>
        <a href={downloadUrl} target="_blank" rel="noreferrer" className="button-primary">
          Get Dynamic Droid <ArrowUpRight aria-hidden="true" />
        </a>
      </section>
      <footer className="site-footer release-footer">
        <Link to="/" className="brand-lockup">
          <span className="brand-mark" aria-hidden="true">
            <span />
          </span>
          <span className="brand-name">
            dynamic<span>droid</span>
          </span>
        </Link>
        <span>Built for the little moments.</span>
        <a
          href="https://github.com/kavinagarwalagarwal-coder/dynamic-island-polish"
          target="_blank"
          rel="noreferrer"
        >
          Open source on GitHub <Github aria-hidden="true" />
        </a>
      </footer>
    </main>
  );
}
