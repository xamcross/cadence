import axe from 'axe-core';

/**
 * F14 accessibility test helper. Runs axe-core against a component fixture element under the WCAG 2.2 AA
 * rule set and returns the violations. Two mechanics matter (research D1/D3):
 *  - axe.run is async — callers MUST await it (after fixture.detectChanges()).
 *  - the element MUST be attached to the live document or axe's colour-contrast / visibility rules
 *    silently no-op on a detached TestBed root. attachToBody/detachFromBody bracket that.
 *
 * Note: axe's experimental `target-size` rule is NOT part of these WCAG tags, so 2.5.8 / the 44px
 * product rule is verified by an explicit computed-size test, not here.
 */
const WCAG_2_2_AA_TAGS = ['wcag2a', 'wcag2aa', 'wcag21a', 'wcag21aa', 'wcag22aa'];

/** Attach a fixture's native element to document.body so axe can evaluate real layout/contrast. */
export function attachToBody(el: HTMLElement): void {
  document.body.appendChild(el);
}

/** Remove a previously attached element (call in afterEach). */
export function detachFromBody(el: HTMLElement): void {
  if (el.parentNode === document.body) {
    document.body.removeChild(el);
  }
}

/** Run an axe WCAG 2.2 AA audit and resolve with the violations array (empty array == pass). */
export async function axeViolations(el: HTMLElement): Promise<axe.Result[]> {
  const results = await axe.run(el, { runOnly: { type: 'tag', values: WCAG_2_2_AA_TAGS } });
  return results.violations;
}
