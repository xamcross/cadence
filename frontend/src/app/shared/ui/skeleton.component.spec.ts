import { ComponentFixture, TestBed } from '@angular/core/testing';
import { SkeletonComponent } from './skeleton.component';
import { attachToBody, axeViolations, detachFromBody } from '../../../testing/axe';

describe('SkeletonComponent', () => {
  let fixture: ComponentFixture<SkeletonComponent>;
  let el: HTMLElement;

  beforeEach(() => {
    TestBed.configureTestingModule({ imports: [SkeletonComponent] });
    fixture = TestBed.createComponent(SkeletonComponent);
    el = fixture.nativeElement as HTMLElement;
    attachToBody(el);
  });

  afterEach(() => detachFromBody(el));

  it('renders `rows` skeleton blocks for the chosen variant', () => {
    fixture.componentRef.setInput('variant', 'table');
    fixture.componentRef.setInput('rows', 3);
    fixture.detectChanges();
    expect(el.querySelectorAll('.skeleton__block').length).toBe(3);
    expect(el.querySelector('.skeleton-group--table')).not.toBeNull();
  });

  it('exposes a polite, busy status region with an SR-only Loading label', () => {
    fixture.detectChanges();
    const group = el.querySelector('.skeleton-group');
    expect(group?.getAttribute('role')).toBe('status');
    expect(group?.getAttribute('aria-busy')).toBe('true');
    expect(el.querySelector('.visually-hidden')?.textContent?.trim()).toBe('Loading…');
  });

  it('hides the shimmer blocks from assistive tech', () => {
    fixture.detectChanges();
    el.querySelectorAll('.skeleton__block').forEach((b) => {
      expect(b.getAttribute('aria-hidden')).toBe('true');
    });
  });

  it('defaults to 5 rows and the lines variant', () => {
    fixture.detectChanges();
    expect(el.querySelectorAll('.skeleton__block').length).toBe(5);
    expect(el.querySelector('.skeleton-group--lines')).not.toBeNull();
  });

  it('has zero axe WCAG 2.2 AA violations', async () => {
    fixture.detectChanges();
    const violations = await axeViolations(el);
    expect(violations).withContext(violations.map((v) => v.id).join(', ')).toEqual([]);
  });
});
