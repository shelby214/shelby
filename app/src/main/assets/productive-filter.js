// Shelby - productive content filter
// Edit the lists below, rebuild, and reinstall the APK to tune your feed.
(() => {
  'use strict';

  if (!/(^|\.)youtube\.com$/.test(location.hostname) || location.hostname === 'music.youtube.com') {
    return;
  }

  if (window.__ytProductiveFilter) {
    window.__ytProductiveFilter.schedule();
    return;
  }

  const CONFIG = {
    // Strict mode hides every video that does not match an allowed term or channel.
    strict: true,

    allowedKeywords: [
      // Technology, programming, engineering, and science
      'technology', 'tech news', 'programming', 'coding', 'developer',
      'software engineering', 'computer science', 'web development',
      'android development', 'ios development', 'javascript', 'typescript',
      'python', 'java ', 'kotlin', 'swiftui', 'react ', 'node.js', 'database',
      'sql ', 'system design', 'data structures', 'algorithm', 'open source',
      'linux', 'cybersecurity', 'cloud computing', 'devops', 'artificial intelligence',
      'machine learning', 'deep learning', 'generative ai', 'robotics', 'electronics',
      'physics', 'chemistry', 'biology', 'mathematics', 'science explained',

      // Education and learning
      'tutorial', 'course', 'lecture', 'lesson', 'learn ', 'explained',
      'study ', 'study tips', 'exam preparation', 'interview preparation',
      'documentary', 'case study', 'research', 'book summary', 'audiobook',
      'history of', 'economics', 'finance', 'investing', 'business',
      'entrepreneurship', 'startup', 'marketing', 'communication skills',
      'english speaking', 'language learning',

      // Productivity, self-improvement, career, and motivation
      'productivity', 'productive', 'time management', 'deep work', 'focus',
      'discipline', 'habit', 'goal setting', 'self improvement', 'personal growth',
      'career', 'resume', 'job interview', 'leadership', 'critical thinking',
      'problem solving', 'motivation', 'motivational', 'inspiration', 'inspirational',
      'mindset', 'success', 'confidence', 'stoicism', 'mental health', 'meditation',
      'fitness education', 'health explained'
    ],

    alwaysAllowChannels: [
      'freecodecamp', 'fireship', 'veritasium', 'vsauce', 'ted', 'tedx',
      'khan academy', 'mit opencourseware', 'stanford', 'harvard', 'coursera',
      'codewithharry', 'apna college', 'geekyranjit', 'mkbhd', 'linus tech tips',
      'ali abdaal', 'cal newport', 'james clear', 'andrew huberman',
      'simon sinek', 'mel robbins', 'naval ravikant', 'sandeep maheshwari'
    ],

    blockedKeywords: [
      'prank', 'reaction video', 'roast', 'comedy', 'funny moments', 'meme',
      'celebrity gossip', 'gossip', 'paparazzi', 'reality show', 'movie trailer',
      'official trailer', 'teaser trailer', 'full movie', 'movie scene',
      'movie explained', 'film explained', 'movie recap', 'film recap', 'web series',
      'cinema', 'nightlife', 'night life', 'film criticism', 'sci-fi film',
      'music video', 'official video song', 'official song', 'lyrics video',
      'dance video', 'gaming', 'gameplay', 'walkthrough game', 'live stream gaming',
      'unboxing mystery', 'challenge video', 'daily vlog', 'family vlog',
      'travel vlog', 'food vlog', 'entertainment news', 'red carpet', 'award show',
      'highlights match', 'sports highlights', 'cartoon', 'anime edit'
    ]
  };

  const CARD_SELECTOR = [
    'ytm-rich-item-renderer',
    'ytm-lockup-view-model',
    'yt-lockup-view-model',
    'ytm-video-lockup-view-model',
    'yt-video-lockup-view-model',
    'ytm-video-with-context-renderer',
    'ytm-compact-video-renderer',
    'ytm-playlist-video-renderer',
    'ytm-reel-item-renderer',
    'ytd-rich-item-renderer',
    'ytd-video-renderer',
    'ytd-compact-video-renderer',
    'ytd-grid-video-renderer',
    'ytd-playlist-video-renderer'
  ].join(',');

  const normalize = value => (value || '').toLocaleLowerCase().replace(/\s+/g, ' ').trim();
  const includesAny = (text, terms) => terms.some(term => text.includes(term));

  const style = document.createElement('style');
  style.id = 'yt-productive-filter-style';
  style.textContent = '.yt-productive-hidden{display:none!important}';
  document.documentElement.appendChild(style);

  const filter = {
    pending: false,

    cardText(card) {
      const extra = [...card.querySelectorAll('[title],[aria-label]')]
        .map(node => `${node.getAttribute('title') || ''} ${node.getAttribute('aria-label') || ''}`)
        .join(' ');
      return normalize(`${card.textContent || ''} ${extra}`);
    },

    isVideoCard(card) {
      return !!card.querySelector('a[href*="/watch"],a[href*="/shorts/"]');
    },

    shouldShow(card) {
      const text = this.cardText(card);
      if (!text) return !CONFIG.strict;
      if (includesAny(text, CONFIG.blockedKeywords)) return false;
      if (includesAny(text, CONFIG.alwaysAllowChannels)) return true;
      if (includesAny(text, CONFIG.allowedKeywords)) return true;
      return !CONFIG.strict;
    },

    run() {
      this.pending = false;
      document.querySelectorAll(CARD_SELECTOR).forEach(card => {
        if (!this.isVideoCard(card)) return;
        card.classList.toggle('yt-productive-hidden', !this.shouldShow(card));
      });
    },

    schedule() {
      if (this.pending) return;
      this.pending = true;
      requestAnimationFrame(() => this.run());
    }
  };

  window.__ytProductiveConfig = CONFIG;
  window.__ytProductiveFilter = filter;
  new MutationObserver(() => filter.schedule()).observe(document.documentElement, {
    childList: true,
    subtree: true,
    characterData: true
  });
  document.addEventListener('yt-navigate-finish', () => filter.schedule());
  window.addEventListener('popstate', () => filter.schedule());
  filter.run();
})();
