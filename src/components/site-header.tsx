import { useState } from "react";
import { ArrowUpRight, Menu, X } from "lucide-react";
import { Link } from "@tanstack/react-router";

export function SiteHeader() {
  const [menuOpen, setMenuOpen] = useState(false);

  return (
    <header className="site-header">
      <div className="site-header-inner">
        <Link
          to="/"
          className="brand-lockup"
          aria-label="Dynamic Droid home"
          onClick={() => setMenuOpen(false)}
        >
          <span className="brand-mark" aria-hidden="true">
            <span />
          </span>
          <span className="brand-name">
            dynamic<span>droid</span>
          </span>
        </Link>
        <button
          className="mobile-menu-toggle"
          type="button"
          aria-label={menuOpen ? "Close navigation menu" : "Open navigation menu"}
          aria-expanded={menuOpen}
          onClick={() => setMenuOpen((open) => !open)}
        >
          {menuOpen ? <X /> : <Menu />}
        </button>
        <nav className={`site-nav${menuOpen ? " is-open" : ""}`} aria-label="Main navigation">
          <a href="/#motion" onClick={() => setMenuOpen(false)}>
            The motion
          </a>
          <a href="/#features" onClick={() => setMenuOpen(false)}>
            Features
          </a>
          <Link to="/releases" onClick={() => setMenuOpen(false)}>
            Releases
          </Link>
          <Link className="nav-download" to="/releases" onClick={() => setMenuOpen(false)}>
            Get the app <ArrowUpRight aria-hidden="true" />
          </Link>
        </nav>
      </div>
    </header>
  );
}
