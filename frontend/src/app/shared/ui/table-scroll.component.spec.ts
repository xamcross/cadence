import { Component } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { TableScrollComponent } from './table-scroll.component';
import { attachToBody, axeViolations, detachFromBody } from '../../../testing/axe';

@Component({
  standalone: true,
  imports: [TableScrollComponent],
  template: `
    <app-table-scroll ariaLabel="Candidates">
      <table class="table">
        <thead><tr><th scope="col">Name</th></tr></thead>
        <tbody><tr><td>Dana</td></tr></tbody>
      </table>
    </app-table-scroll>
  `
})
class HostComponent {}

describe('TableScrollComponent', () => {
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

  it('wraps projected content in a keyboard-focusable .table-scroll region', () => {
    const wrap = el.querySelector('.table-scroll') as HTMLElement | null;
    expect(wrap).not.toBeNull();
    expect(wrap?.getAttribute('tabindex')).toBe('0');
    expect(wrap?.getAttribute('aria-label')).toBe('Candidates');
    expect(wrap?.querySelector('table.table')).not.toBeNull();
  });

  it('has zero axe WCAG 2.2 AA violations', async () => {
    const violations = await axeViolations(el);
    expect(violations).withContext(violations.map((v) => v.id).join(', ')).toEqual([]);
  });
});
