import { Component } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { EmptyStateComponent } from './empty-state.component';
import { attachToBody, axeViolations, detachFromBody } from '../../../testing/axe';

@Component({
  standalone: true,
  imports: [EmptyStateComponent],
  template: `
    <app-empty-state heading="No candidates yet" body="Import a CSV to get started.">
      <a class="btn btn--primary" href="/admin/csv-import">Import candidates</a>
    </app-empty-state>
  `
})
class HostComponent {}

describe('EmptyStateComponent', () => {
  let fixture: ComponentFixture<HostComponent>;
  let el: HTMLElement;

  beforeEach(() => {
    TestBed.configureTestingModule({ imports: [HostComponent] });
    fixture = TestBed.createComponent(HostComponent);
    el = fixture.nativeElement as HTMLElement;
    attachToBody(el);
    fixture.detectChanges();
  });

  afterEach(() => detachFromBody(el));

  it('renders the heading and body inside the .empty primitive', () => {
    expect(el.querySelector('.empty .empty__title')?.textContent?.trim()).toBe('No candidates yet');
    expect(el.querySelector('.empty__body')?.textContent?.trim()).toBe('Import a CSV to get started.');
  });

  it('projects a CTA into the actions slot', () => {
    expect(el.querySelector('.empty__actions a')?.textContent?.trim()).toBe('Import candidates');
  });

  it('omits the body paragraph when no body is provided', () => {
    const f2 = TestBed.createComponent(EmptyStateComponent);
    f2.componentRef.setInput('heading', 'Empty');
    f2.detectChanges();
    expect((f2.nativeElement as HTMLElement).querySelector('.empty__body')).toBeNull();
  });

  it('has zero axe WCAG 2.2 AA violations', async () => {
    const violations = await axeViolations(el);
    expect(violations).withContext(violations.map((v) => v.id).join(', ')).toEqual([]);
  });
});
