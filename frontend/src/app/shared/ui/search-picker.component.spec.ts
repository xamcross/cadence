import { Component } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { By } from '@angular/platform-browser';
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

  // Fix 2: editing the first character after a pick must NOT blank the input. onInput clears the
  // committed selection and emits null; the parent echoes '' back into [value], and the setter used
  // to unconditionally wipe _text -- clobbering the character the user just typed.
  it('keeps the typed text (does not blank) when editing right after a selection', () => {
    const picker = fixture.debugElement.query(By.directive(SearchPickerComponent)).componentInstance as SearchPickerComponent;
    let nullEmits = 0;
    picker.valueChange.subscribe((v) => { if (v === null) nullEmits++; });

    input().dispatchEvent(new Event('focus'));
    type('dana');
    (el.querySelector('.picker__opt') as HTMLElement).click();
    fixture.detectChanges();
    expect(input().value).toBe('Dana Okafor');

    type('Dana O'); // first edit after the pick
    expect(input().value).toBe('Dana O'); // not blanked by the '' echo
    expect(host.picked).toBeNull();
    expect(nullEmits).toBe(1); // emitted null exactly once (on the edit)
  });

  // Fix 2 (partner): the after-success external reset ([value] set to '') still clears the picker,
  // because a committed selection is present when the parent resets it.
  it('still clears on an external reset after a selection', () => {
    input().dispatchEvent(new Event('focus'));
    type('dana');
    (el.querySelector('.picker__opt') as HTMLElement).click();
    fixture.detectChanges();
    expect(input().value).toBe('Dana Okafor');

    host.picked = ''; // parent resets after a successful action
    fixture.detectChanges();
    expect(input().value).toBe('');
  });

  // Fix 3: `options` is a signal input, so reassigning the host's options WITHOUT a query change
  // invalidates the `filtered` memo and re-renders the list.
  it('reflects reassigned options without a query change (options is a reactive signal input)', () => {
    input().dispatchEvent(new Event('focus'));
    fixture.detectChanges();
    expect(el.querySelectorAll('.picker__opt').length).toBe(3);

    host.opts = [{ id: 'z1', label: 'Solo Option' }];
    fixture.detectChanges();
    const opts = el.querySelectorAll('.picker__opt');
    expect(opts.length).toBe(1);
    expect(opts[0].textContent).toContain('Solo Option');
  });

  // Fix 5: aria-controls/aria-expanded must not claim a listbox when there is nothing to point at
  // (filtered().length === 0 renders no <ul>).
  it('does not claim a listbox when open with zero matches', () => {
    input().dispatchEvent(new Event('focus'));
    type('zzzzz'); // matches nothing
    expect(el.querySelector('.picker__list')).toBeNull();
    expect(input().getAttribute('aria-controls')).toBeNull();
    expect(input().getAttribute('aria-expanded')).toBe('false');
  });

  it('has zero axe WCAG 2.2 AA violations while open', async () => {
    input().dispatchEvent(new Event('focus'));
    type('a');
    const v = await axeViolations(el);
    expect(v).withContext(v.map((x) => x.id).join(', ')).toEqual([]);
  });
});
