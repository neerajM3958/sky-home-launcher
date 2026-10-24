#!/usr/bin/env python3
"""Compares two folders of screenshots made by tools/shots.sh.

    python3 tools/shotdiff.py <before> <after> [--noise <folder>]

For every screen: the mean difference per pixel (0 to 255) and the share of pixels that differ by more than 8. With
--noise (a second run of the *before* build), the differences of the same build with itself are printed beside them, so a
real change can be told from the animated sky, the clock and the picks. Writes <after>/diff-<name>.png (differences in red).
Needs Pillow: pip install pillow."""
import os
import sys

try:
    from PIL import Image, ImageChops, ImageStat
except ImportError:
    sys.exit('This needs Pillow: pip install pillow')


def compare(a_path, b_path, save=None):
    a, b = Image.open(a_path).convert('RGB'), Image.open(b_path).convert('RGB')
    if a.size != b.size:
        return None
    diff = ImageChops.difference(a, b)
    r, g, bl = diff.split()
    worst = ImageChops.lighter(ImageChops.lighter(r, g), bl)       # per pixel, the largest of the three channels' differences
    mean = sum(ImageStat.Stat(diff).mean) / 3
    over = sum(worst.histogram()[9:]) / (a.width * a.height)
    if save:
        mask = diff.convert('L').point(lambda v: 255 if v > 8 else 0)
        red = Image.new('RGB', a.size, (255, 0, 0))
        Image.composite(red, Image.blend(a, Image.new('RGB', a.size, (0, 0, 0)), 0.6), mask).save(save)
    return mean, over


def main():
    args = sys.argv[1:]
    noise = None
    if '--noise' in args:
        i = args.index('--noise')
        noise = args[i + 1]
        del args[i:i + 2]
    if len(args) != 2:
        sys.exit(__doc__)
    before, after = args
    names = sorted(f[:-4] for f in os.listdir(before) if f.endswith('.png') and not f.startswith('diff-'))
    print('%-18s %9s %9s' % ('screen', 'mean', '>8') + ('   noise: mean    >8' if noise else ''))
    worst = 0
    for n in names:
        r = compare(os.path.join(before, n + '.png'), os.path.join(after, n + '.png'), os.path.join(after, 'diff-' + n + '.png'))
        if r is None:
            print('%-18s (missing or another size)' % n)
            continue
        line = '%-18s %9.3f %8.2f%%' % (n, r[0], r[1] * 100)
        if noise:
            q = compare(os.path.join(before, n + '.png'), os.path.join(noise, n + '.png'))
            line += '   %9.3f %6.2f%%' % (q[0], q[1] * 100) if q else '   -'
        print(line)
        worst = max(worst, r[0])
    print('worst mean difference: %.3f' % worst)


main()
