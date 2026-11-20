// Puts a design picture and an app capture side by side: swift side_by_side.swift design.png app.png out.png
import AppKit
let a = CommandLine.arguments
let l = NSImage(contentsOfFile: a[1])!, r = NSImage(contentsOfFile: a[2])!
let w = 360.0, h = 800.0, gap = 16.0
let out = NSImage(size: NSSize(width: w * 2 + gap, height: h))
out.lockFocus()
NSColor.gray.setFill(); NSRect(x: 0, y: 0, width: w * 2 + gap, height: h).fill()
l.draw(in: NSRect(x: 0, y: 0, width: w, height: h)); r.draw(in: NSRect(x: w + gap, y: 0, width: w, height: h))
out.unlockFocus()
let rep = NSBitmapImageRep(data: out.tiffRepresentation!)!
try! rep.representation(using: .png, properties: [:])!.write(to: URL(fileURLWithPath: a[3]))
