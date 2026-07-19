import { Component } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { PageHeaderComponent } from './page-header.component';
import { attachToBody, axeViolations, detachFromBody } from '../../../testing/axe';

@Component({
  standalone: true,
  imports: [PageHeaderComponent],
  template: `
    <app-page-header eyebrow="Your work" heading="Pipeline" subtitle="Your list"
                     backLink="/app" backLabel="Back">
      <button actions type="button" class="btn btn--primary">New</button>
    </app-page-header>
  `
})
class HostComponent {}

describe('PageHeaderComponent', () => {
  let fixture: ComponentFixture<HostComponent>;
  let el: HTMLElement;

  beforeEach(() => {
    TestBed.configureTestingModule({ imports: [HostComponent], providers: [provideRouter([])] });
    fixture = TestBed.createComponent(HostComponent);
    el = fixture.nativeElement as HTMLElement;
    attachToBody(el);
    fixture.detectChanges();
  });

  afterEach(() => detachFromBody(el));

  it('renders the heading in an <h1> inside the .page__head masthead', () => {
    expect(el.querySelector('.page__head h1')?.textContent?.trim()).toBe('Pipeline');
  });

  it('renders the eyebrow and subtitle when provided', () => {
    expect(el.querySelector('.eyebrow')?.textContent?.trim()).toBe('Your work');
    expect(el.querySelector('.page__subtitle')?.textContent?.trim()).toBe('Your list');
  });

  it('projects [actions] content into the header', () => {
    expect(el.querySelector('.page__actions button')?.textContent?.trim()).toBe('New');
  });

  it('renders a routed back-link when backLink is set', () => {
    const back = el.querySelector('.page__back') as HTMLAnchorElement | null;
    expect(back?.textContent?.trim()).toBe('Back');
    expect(back?.getAttribute('href')).toBe('/app');
  });

  it('has zero axe WCAG 2.2 AA violations', async () => {
    const violations = await axeViolations(el);
    expect(violations).withContext(violations.map((v) => v.id).join(', ')).toEqual([]);
  });
});
