# r11y demo — HTML to clean Markdown, fast
# Render with:  vhs demo.tape   (produces demo.gif)
# Requires r11y on PATH.

Output demo.gif

Set Shell "zsh"
Set FontFamily "Inconsolata for Powerline"
Set FontSize 22
Set Width 1200
Set Height 640
Set Padding 28
Set Theme "TokyoNight"
Set TypingSpeed 55ms

# Setup (not recorded): real comments + a clean prompt
Hide
Type "setopt interactive_comments; PROMPT='%F{8}$ %f'; clear"
Enter
Show

Sleep 700ms

Type "# a web page is mostly markup you do not want:"
Enter
Type "curl -s https://github.com/dazld/r11y | wc -c"
Sleep 300ms
Enter
Sleep 2s

Type "# r11y pulls out just the content, as clean Markdown:"
Enter
Type "r11y https://github.com/dazld/r11y | head -n 18"
Sleep 300ms
Enter
Sleep 3500ms

Type "# ...with metadata, in a couple hundred milliseconds:"
Enter
Type "time r11y -m https://github.com/dazld/r11y > r11y.md"
Sleep 300ms
Enter
Sleep 3500ms
