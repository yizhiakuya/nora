const fs = require('fs');
const path = require('path');

const replacements = [
  // Backgrounds
  { from: /bg-\[#f4f5f7\]\s*dark:bg-gray-950/g, to: 'bg-background' },
  { from: /bg-\[#f4f5f7\]\s*dark:bg-background/g, to: 'bg-background' },
  { from: /from-\[#f4f5f7\]\s*dark:from-gray-950/g, to: 'from-background' },
  { from: /via-\[#f4f5f7\]\s*dark:via-gray-950/g, to: 'via-background' },
  { from: /bg-white\s*dark:bg-gray-900/g, to: 'bg-card' },
  { from: /bg-white\s*dark:bg-gray-950/g, to: 'bg-card' },
  { from: /bg-white\s*dark:bg-background/g, to: 'bg-card' },
  { from: /bg-gray-50\s*dark:bg-gray-900/g, to: 'bg-muted' },
  { from: /bg-gray-50\s*dark:bg-gray-950/g, to: 'bg-muted' },
  { from: /bg-gray-100\s*dark:bg-gray-800/g, to: 'bg-muted' },
  { from: /bg-gray-100\s*dark:bg-gray-900/g, to: 'bg-muted' },
  { from: /bg-gray-200\/50\s*dark:bg-gray-800\/50/g, to: 'bg-muted\/50' },
  { from: /bg-gray-50\/50\s*dark:bg-gray-900\/50/g, to: 'bg-muted\/30' },
  
  // Borders
  { from: /border-gray-200\s*dark:border-gray-800/g, to: 'border-border' },
  { from: /border-gray-100\s*dark:border-gray-800/g, to: 'border-border' },
  { from: /border-gray-200\s*dark:border-gray-700/g, to: 'border-border' },
  { from: /border-gray-300\s*dark:border-gray-600/g, to: 'border-border' },
  
  // Hover & Group Hover Backgrounds
  { from: /hover:bg-white\s*dark:hover:bg-gray-800/g, to: 'hover:bg-card' },
  { from: /hover:bg-gray-50\s*dark:hover:bg-gray-800/g, to: 'hover:bg-muted' },
  { from: /hover:bg-gray-100\s*dark:hover:bg-gray-800/g, to: 'hover:bg-muted' },
  { from: /hover:bg-gray-100\s*dark:hover:bg-gray-700/g, to: 'hover:bg-muted\/80' },
  { from: /hover:border-gray-200\s*dark:hover:border-gray-800/g, to: 'hover:border-border' },

  // Texts
  { from: /text-gray-900\s*dark:text-gray-100/g, to: 'text-foreground' },
  { from: /text-gray-900\s*dark:text-gray-50/g, to: 'text-foreground' },
  { from: /text-gray-800\s*dark:text-gray-100/g, to: 'text-foreground' },
  { from: /text-gray-800\s*dark:text-gray-200/g, to: 'text-foreground' },
  { from: /text-gray-700\s*dark:text-gray-200/g, to: 'text-foreground' },
  { from: /text-gray-600\s*dark:text-gray-300/g, to: 'text-muted-foreground' },
  { from: /text-gray-500\s*dark:text-gray-400/g, to: 'text-muted-foreground' },
  { from: /text-gray-400\s*dark:text-gray-500/g, to: 'text-muted-foreground' },
  { from: /text-gray-400\s*dark:text-gray-600/g, to: 'text-muted-foreground' },
  { from: /text-gray-300\s*dark:text-gray-600/g, to: 'text-muted-foreground\/60' },
  
  // Hover Texts
  { from: /hover:text-gray-900\s*dark:hover:text-gray-50/g, to: 'hover:text-foreground' },
  { from: /hover:text-gray-800\s*dark:hover:text-gray-100/g, to: 'hover:text-foreground' },
  { from: /hover:text-gray-700\s*dark:hover:text-gray-200/g, to: 'hover:text-foreground' },
  { from: /hover:text-gray-600\s*dark:hover:text-gray-300/g, to: 'hover:text-foreground' },
  { from: /group-hover:text-gray-600\s*dark:group-hover:text-gray-300/g, to: 'group-hover:text-foreground' },
  { from: /group-hover:text-gray-500\s*dark:group-hover:text-gray-400/g, to: 'group-hover:text-foreground' },
  
  // Placeholders
  { from: /placeholder-gray-400\s*dark:placeholder-gray-500/g, to: 'placeholder:text-muted-foreground' }
];

function processDirectory(dir) {
  const files = fs.readdirSync(dir);
  for (const file of files) {
    const fullPath = path.join(dir, file);
    if (fs.statSync(fullPath).isDirectory()) {
      processDirectory(fullPath);
    } else if (fullPath.endsWith('.tsx') || fullPath.endsWith('.ts')) {
      let content = fs.readFileSync(fullPath, 'utf8');
      let originalContent = content;
      
      for (const rep of replacements) {
        content = content.replace(rep.from, rep.to);
      }
      
      if (content !== originalContent) {
        fs.writeFileSync(fullPath, content, 'utf8');
        console.log(`Updated ${fullPath}`);
      }
    }
  }
}

processDirectory('nora-web/src/app');
processDirectory('nora-web/src/components');
