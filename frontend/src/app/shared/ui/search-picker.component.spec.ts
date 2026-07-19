import { Component } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { PickerOption, SearchPickerComponent } from './search-picker.component';
import { attachToBody, axeViolations, detachFromBody } from '../../../testing/axe';

@Component({
  standalone: true,
  imports: [SearchPickerComponent],
  template: `<app-search-picker [options]="opts" label="Candidate" placeholder="Search…"
              [value]="picked" (valueChange)="picked = $event"></app-search-picker>`
})
class HostComponent {
  opts: PickerOption[] = [
    { id: 'c1', label: 'Dana Okafor', hint: 'Technical' },
    { id: 'c2', label: 'Marek Novak', hint: 'Screen' },
    { id: 'c3', label: 'Priya Shah' }
  ];
  picked: string | null = null;
}

describe('SearchPickerComponent', () => {
  let fixture: ComponentFixture<HostComponent>;
  let host: HostComponent;
  let el: HTMLElement;

  const input = () => el.querySelector('.picker__input') as HTMLInputElement;
  const type = (v: string) => { input().value = v; input().dispatchEvent(new Event('input')); fixture.detectChanges(); };

  beforeEach(() => {
    TestBed.configureTestingModule({ imports: [HostComponent] });
    fixture = TestBed.createComponent(HostComponent);
    host = fixture.componentInstance;
    el = fixture.nativeElement as HTMLElement;
    attachToBody(el);
    fixture.detectChanges();
  });
  afterEach(() => detachFromBody(el));

  it('renders a combobox input', () => {
    expect(input().getAttribute('role')).toBe('combobox');
    expect(input().getAttribute('aria-expanded')).toBe('false');
  });

  it('filters options by case-insensitive substring as you type', () => {
    input().dispatchEvent(new Event('focus'));
    type('nov');
    const opts = el.querySelectorAll('.picker__opt');
    expect(opts.length).toBe(1);
    expect(opts[0].textContent).toContain('Marek Novak');
  });

  it('selecting an option emits its id and shows its label', () => {
    input().dispatchEvent(new Event('focus'));
    type('dana');
    (el.querySelector('.picker__opt') as HTMLElement).click();
    fixture.detectChanges();
    expect(host.picked).toBe('c1');
    expect(input().value).toBe('Dana Okafor');
    expect(el.querySelector('.picker__list')).toBeNull(); // closed after pick
  });

  it('keyboard: ArrowDown + Enter selects the active option', () => {
    input().dispatchEvent(new Event('focus'));
    type('a'); // matches all three
    input().dispatchEvent(new KeyboardEvent('keydown', { key: 'ArrowDown' }));
    input().dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter' }));
    fixture.detectChanges();
    expect(host.picked).not.toBeNull();
  });

  it('editing after a selection clears the emitted value', () => {
    input().dispatchEvent(new Event('focus'));
    type('dana');
    (el.querySelector('.picker__opt') as HTMLElement).click();
    fixture.detectChanges();
    expect(host.picked).toBe('c1');
    type('dan');
    expect(host.picked).toBeNull();
  });

  it('has zero axe WCAG 2.2 AA violations while open', async () => {
    input().dispatchEvent(new Event('focus'));
    type('a');
    const v = await axeViolations(el);
    expect(v).withContext(v.map((x) => x.id).join(', ')).toEqual([]);
  });
});
