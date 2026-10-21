export function CoreArt() {
  return (
    <div className="core-art" aria-hidden="true">
      <svg viewBox="0 0 500 400" fill="none">
        <defs>
          <linearGradient
            id="edge"
            x1="170"
            y1="70"
            x2="340"
            y2="330"
            gradientUnits="userSpaceOnUse"
          >
            <stop stopColor="#b3f3ff" />
            <stop offset=".5" stopColor="#478ebd" />
            <stop offset="1" stopColor="#8b77cf" />
          </linearGradient>
          <linearGradient
            id="face"
            x1="200"
            y1="100"
            x2="300"
            y2="320"
            gradientUnits="userSpaceOnUse"
          >
            <stop stopColor="#1c6484" stopOpacity=".7" />
            <stop offset="1" stopColor="#15233e" stopOpacity=".15" />
          </linearGradient>
          <radialGradient id="halo">
            <stop stopColor="#66d8ee" stopOpacity=".14" />
            <stop offset="1" stopColor="#66d8ee" stopOpacity="0" />
          </radialGradient>
        </defs>
        <ellipse cx="250" cy="220" rx="225" ry="170" fill="url(#halo)" />
        <g className="art-grid">
          <path d="M10 305h480M40 337h420M83 365h330M250 230 30 380m220-150L470 380M250 230 140 390m110-160 110 160" />
          <ellipse cx="250" cy="276" rx="176" ry="65" />
          <ellipse cx="250" cy="276" rx="142" ry="48" />
          <path d="M74 276h40m272 0h40M250 205v-15m0 151v16" />
          <path d="M80 130h45l32 32m187 74 30-30h50M140 75v30m220-10h-30" />
        </g>
        <g stroke="url(#edge)" strokeLinejoin="round">
          <path
            fill="url(#face)"
            d="m250 54 88 82-23 116-65 74-65-74-23-116Z"
            strokeWidth="1.5"
          />
          <path
            d="m250 54 30 99-30 173-30-173 30-99ZM162 136l88 40 88-40m-153 116 65-76 65 76M162 136l58 17 30 23 30-23 58-17"
            strokeOpacity=".55"
          />
          <path
            d="m250 111 36 54-36 83-36-83Z"
            fill="url(#face)"
            strokeWidth="1.5"
          />
          <path
            d="m250 111 12 54-12 83-12-83 12-54m-36 54h72"
            strokeOpacity=".6"
          />
          <path
            d="m250 83 0-12M174 141l-9-4m161 4 9-4M250 309v-14"
            strokeWidth="3"
          />
          <ellipse
            cx="250"
            cy="276"
            rx="176"
            ry="65"
            strokeDasharray="90 460"
            strokeWidth="2"
          />
          <path d="m92 201 3-10 10-3m292-37 10 3 3 10" />
        </g>
        <g className="art-points">
          <circle cx="92" cy="273" r="3" />
          <circle cx="391" cy="314" r="3" />
          <circle cx="250" cy="54" r="3" />
          <path d="M250 31v8m-4-4h8m-67 264v8m-4-4h8m197-78v8m-4-4h8" />
        </g>
        <g className="art-label">
          <text x="65" y="119">
            01 / DISCOVER
          </text>
          <text x="357" y="193">
            02 / REFINE
          </text>
          <text x="285" y="374">
            03 / TRANSCEND
          </text>
        </g>
      </svg>
      <span className="core-caption">
        CAPABILITY IS BUILT. <b>ONE INSIGHT AT A TIME.</b>
      </span>
    </div>
  );
}
export function MissionArt() {
  return (
    <svg
      className="mission-art"
      viewBox="0 0 170 170"
      fill="none"
      aria-hidden="true"
    >
      <g stroke="currentColor">
        <path d="m85 10 65 37v76l-65 37-65-37V47Z" opacity=".2" />
        <path d="m85 30 48 27v56l-48 27-48-27V57Z" opacity=".45" />
        <path d="m85 49 32 18v36l-32 18-32-18V67Z" />
        <path
          d="m72 75-11 10 11 10m26-20 11 10-11 10m-9-24-8 48"
          strokeWidth="2"
        />
        <path d="M85 10v20m65 17-17 10m17 66-17-10M85 140v20m-65-37 17-10M20 47l17 10" />
      </g>
    </svg>
  );
}
